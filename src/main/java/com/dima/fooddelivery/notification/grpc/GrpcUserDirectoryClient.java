package com.dima.fooddelivery.notification.grpc;

import com.dima.fooddelivery.grpc.identity.v1.GetUserContactRequest;
import com.dima.fooddelivery.grpc.identity.v1.UserContact;
import com.dima.fooddelivery.grpc.identity.v1.UserDirectoryServiceGrpc;
import io.grpc.Channel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link UserDirectoryClient} поверх gRPC к identity-service.
 *
 * <p><b>Дедлайн обязателен</b>, как и у остальных gRPC-клиентов платформы: вызов стоит внутри
 * транзакции доставки, и зависший identity-service держал бы поток обработчика и соединение с
 * базой. Предохранитель на канале ({@code CircuitBreakerClientInterceptor}) отвечает сразу,
 * когда сосед лежит; здесь это тот же {@code UNAVAILABLE}.
 *
 * <p>NOT_FOUND — «пользователя нет», остальное — «спросить не удалось». Разница существенна: первое
 * значит, что письмо слать некому и повторять незачем, второе — что повторить стоит.
 */
@Slf4j
@Component
public class GrpcUserDirectoryClient implements UserDirectoryClient {

    /** Прогрев вне цепочки доставки, поэтому ждать можно намного дольше рабочего дедлайна. */
    private static final Duration WARMUP_DEADLINE = Duration.ofSeconds(15);

    private final UserDirectoryServiceGrpc.UserDirectoryServiceBlockingStub stub;
    private final Duration deadline;

    @Autowired
    public GrpcUserDirectoryClient(
            GrpcChannelFactory channels,
            @Value("${app.grpc.identity.deadline:PT2S}") Duration deadline
    ) {
        // Имя канала — ключ в spring.grpc.client.channels: адрес живёт в конфигурации.
        this(channels.createChannel("identity"), deadline);
    }

    /** Для тестов: канал к серверу в памяти процесса вместо настоящего адреса. */
    GrpcUserDirectoryClient(Channel channel, Duration deadline) {
        this.stub = UserDirectoryServiceGrpc.newBlockingStub(channel);
        this.deadline = deadline;
    }

    /**
     * Прогрев канала после старта.
     *
     * <p>Самый первый вызов платит за всё сразу: TLS-рукопожатие по взаимному TLS, HTTP/2, загрузка
     * классов protobuf и gRPC, холодный JIT. На практике он не укладывался в дедлайн в 2 секунды, и
     * первое письмо после старта уходило с повтором через очередь. Пробный вызов делается в
     * отдельном потоке и не задерживает запуск; его результат не важен — важно, что соединение
     * и классы уже готовы. Если identity-service ещё не поднялся, прогрев молча пропускается, а
     * канал сам переподключится при первом настоящем вызове.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warmUpAfterStartup() {
        Thread.ofVirtual().name("grpc-identity-warmup").start(() -> {
            try {
                // Пользователя 0 нет: NOT_FOUND означает, что весь путь прошёл и сервер ответил.
                stub.withDeadlineAfter(WARMUP_DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                        .getUserContact(GetUserContactRequest.newBuilder().setUserId(0).build());
            } catch (RuntimeException exception) {
                log.debug("Прогрев канала к identity-service: {}", exception.getMessage());
            }
        });
    }

    @Override
    public Optional<UserContactInfo> findContact(Long userId) {
        try {
            UserContact contact = stub
                    .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .getUserContact(GetUserContactRequest.newBuilder().setUserId(userId).build());

            return Optional.of(new UserContactInfo(
                    contact.getUserId(), contact.getEmail(), contact.getFullName(), contact.getEnabled()
            ));
        } catch (StatusRuntimeException exception) {
            if (exception.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }

            // Адрес в лог не пишем: он персональные данные, а номер пользователя достаточен для разбора.
            log.warn("Не удалось получить контакт пользователя: userId={}, status={}", userId, exception.getStatus());

            throw new DirectoryUnavailableException(
                    "Сервис пользователей недоступен, контакт не получен: " + exception.getStatus().getCode(),
                    exception
            );
        }
    }
}
