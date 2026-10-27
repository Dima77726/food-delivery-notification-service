package com.dima.fooddelivery.notification.webhook;

import com.dima.fooddelivery.grpc.identity.v1.CheckRestaurantAccessRequest;
import com.dima.fooddelivery.grpc.identity.v1.RestaurantAccessServiceGrpc;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Права на ресторан: тот же вопрос к identity-service по gRPC, что задают каталог и заказы.
 * Список ресторанов владельца меняется чаще, чем живёт токен, поэтому в токене его нет.
 */
@Slf4j
@Component
public class GrpcRestaurantAccess implements RestaurantAccess {

    private final RestaurantAccessServiceGrpc.RestaurantAccessServiceBlockingStub stub;
    private final Duration deadline;

    public GrpcRestaurantAccess(GrpcChannelFactory channels, @Value("${app.grpc.identity.deadline:PT2S}") Duration deadline) {
        this.stub = RestaurantAccessServiceGrpc.newBlockingStub(channels.createChannel("identity"));
        this.deadline = deadline;
    }

    @Override
    public long requireManager(long restaurantId) {
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        long userId = Long.parseLong(jwt.getSubject());

        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null && roles.contains("ADMIN")) {
            return userId;
        }

        boolean manages;
        try {
            manages = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                    .checkRestaurantAccess(CheckRestaurantAccessRequest.newBuilder()
                            .setUserId(userId).setRestaurantId(restaurantId).build())
                    .getManages();
        } catch (StatusRuntimeException exception) {
            log.error("Не удалось проверить права на ресторан: {}", exception.getStatus());
            throw new WebhookErrors.Unavailable("Сервис пользователей недоступен, повторите позже");
        }
        if (!manages) {
            throw new WebhookErrors.Forbidden("Нет прав на ресторан с id=" + restaurantId);
        }
        return userId;
    }
}
