package com.dima.fooddelivery.notification.grpc;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Предохранитель на исходящих gRPC-вызовах: упавший сосед отдаёт отказ сразу, а не после дедлайна.
 *
 * <p><b>Зачем, если дедлайн уже есть.</b> Дедлайн ограничивает одно ожидание, но не их число.
 * Пока сосед лежит, каждый запрос честно ждёт свои две секунды и держит поток, а нередко и
 * соединение с базой — вызов стоит внутри транзакции. Под нагрузкой пул соединений кончается
 * за секунды, и вместе с соседом отказывает сервис, который в нём вовсе не виноват.
 * Предохранитель после нескольких подряд неудач размыкается и несколько секунд отвечает сразу;
 * потом пропускает пробные вызовы и, если сосед ожил, замыкается обратно.
 *
 * <p><b>Повторов по-прежнему нет.</b> Ровно по той причине, что описана в клиентах: вызов
 * синхронный, повтор к недоступному соседу удвоил бы ожидание перед тем же отказом.
 *
 * <p><b>Перехватчик, а не обёртка в каждом клиенте.</b> Размыкание отдаётся статусом
 * {@code UNAVAILABLE} — тем самым, что вернул бы лежащий сосед, — и уже написанный в клиентах
 * перевод статусов превращает его в 503 без единой правки. Глобальный, как перехватчик метки
 * запроса: новый клиент получает защиту сам, забыть её подключить нельзя.
 *
 * <p><b>Один предохранитель на gRPC-сервис</b> (например, {@code CatalogService}), а не на
 * метод: лежит обычно сосед целиком, и считать неудачи по методам значило бы дольше
 * набирать статистику.
 *
 * <p><b>Что считается неудачей.</b> Только то, что говорит о нездоровье соседа:
 * {@code UNAVAILABLE}, {@code DEADLINE_EXCEEDED}, {@code INTERNAL} и им подобные.
 * {@code NOT_FOUND} и {@code FAILED_PRECONDITION} — нормальные деловые ответы здорового соседа;
 * считай их неудачами, и поток запросов к несуществующему блюду размыкал бы предохранитель
 * при полностью исправном каталоге.
 *
 * <p>Состояние и счётчики вызовов видны в метриках {@code resilience4j_circuitbreaker_*}.
 */
@Component
@GlobalClientInterceptor
public class CircuitBreakerClientInterceptor implements ClientInterceptor {

    private static final Set<Status.Code> UNHEALTHY = EnumSet.of(
            Status.Code.UNAVAILABLE,
            Status.Code.DEADLINE_EXCEEDED,
            Status.Code.INTERNAL,
            Status.Code.UNKNOWN,
            Status.Code.DATA_LOSS,
            Status.Code.RESOURCE_EXHAUSTED
    );

    private final CircuitBreakerRegistry registry;

    @Autowired
    public CircuitBreakerClientInterceptor(
            MeterRegistry meters,
            @Value("${app.grpc.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${app.grpc.circuit-breaker.sliding-window-size:10}") int slidingWindowSize,
            @Value("${app.grpc.circuit-breaker.minimum-calls:5}") int minimumCalls,
            @Value("${app.grpc.circuit-breaker.open-duration:PT10S}") Duration openDuration
    ) {
        this(CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumCalls)
                .waitDurationInOpenState(openDuration)
                .permittedNumberOfCallsInHalfOpenState(3)
                .build());

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
    }

    /** Для тестов: свои пороги и без метрик. */
    CircuitBreakerClientInterceptor(CircuitBreakerConfig config) {
        this.registry = CircuitBreakerRegistry.of(config);
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next
    ) {
        CircuitBreaker breaker = registry.circuitBreaker(method.getServiceName());

        if (!breaker.tryAcquirePermission()) {
            return new RejectedCall<>(Status.UNAVAILABLE.withDescription(
                    "Предохранитель разомкнут: " + method.getServiceName() + " недоступен"
            ));
        }

        long startedAt = System.nanoTime();

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onClose(Status status, Metadata trailers) {
                        long elapsed = System.nanoTime() - startedAt;

                        if (UNHEALTHY.contains(status.getCode())) {
                            breaker.onError(elapsed, TimeUnit.NANOSECONDS, status.asRuntimeException());
                        } else if (status.getCode() == Status.Code.CANCELLED) {
                            // Отмена — решение вызывающего, а не признак здоровья соседа.
                            breaker.releasePermission();
                        } else {
                            breaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                        }

                        super.onClose(status, trailers);
                    }
                }, headers);
            }
        };
    }

    /** Вызов, отклонённый без обращения к соседу: слушатель сразу получает закрытие со статусом. */
    private static final class RejectedCall<ReqT, RespT> extends ClientCall<ReqT, RespT> {

        private final Status status;

        RejectedCall(Status status) {
            this.status = status;
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
            responseListener.onClose(status, new Metadata());
        }

        @Override
        public void request(int numMessages) {
        }

        @Override
        public void cancel(String message, Throwable cause) {
        }

        @Override
        public void halfClose() {
        }

        @Override
        public void sendMessage(ReqT message) {
        }
    }
}
