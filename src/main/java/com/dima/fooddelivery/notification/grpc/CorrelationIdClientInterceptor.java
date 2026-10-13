package com.dima.fooddelivery.notification.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import com.dima.fooddelivery.notification.web.RequestContext;
import org.slf4j.MDC;
import org.springframework.grpc.client.GlobalClientInterceptor;
import org.springframework.stereotype.Component;

/**
 * Переносит сквозную метку запроса в исходящий gRPC-вызов.
 *
 * <p>По HTTP метка едет заголовком {@code X-Correlation-Id}, через Kafka — заголовком сообщения.
 * У gRPC свои заголовки — метаданные вызова, — и без этого перехватчика цепочка рвалась бы
 * на каждом синхронном вызове соседа: в логах соседа запрос выглядел бы ничьим.
 *
 * <p>Глобальный: применяется ко всем каналам, созданным через {@code GrpcChannelFactory}.
 * Забыть подключить его к новому клиенту невозможно.
 */
@Component
@GlobalClientInterceptor
public class CorrelationIdClientInterceptor implements ClientInterceptor {

    /** Имя в метаданных — строчными: gRPC работает поверх HTTP/2, где заголовки только такие. */
    public static final Metadata.Key<String> CORRELATION_ID =
            Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method,
            CallOptions callOptions,
            Channel next
    ) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                // Метку ставит работник очереди в MDC потока (см. NotificationWorker): тот же поток вызывает
                // и gRPC, поэтому она доступна здесь.
                String id = MDC.get(RequestContext.CORRELATION_ID_MDC_KEY);

                if (id != null) {
                    headers.put(CORRELATION_ID, id);
                }

                super.start(responseListener, headers);
            }
        };
    }
}
