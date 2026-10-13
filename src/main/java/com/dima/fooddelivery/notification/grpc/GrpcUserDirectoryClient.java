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
