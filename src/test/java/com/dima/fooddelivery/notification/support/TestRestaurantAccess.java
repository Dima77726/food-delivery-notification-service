package com.dima.fooddelivery.notification.support;

import com.dima.fooddelivery.notification.webhook.RestaurantAccess;
import com.dima.fooddelivery.notification.webhook.WebhookErrors;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Права на рестораны в памяти вместо gRPC к identity-service. */
public class TestRestaurantAccess implements RestaurantAccess {

    private final Set<String> managers = ConcurrentHashMap.newKeySet();

    public void assignManager(long userId, long restaurantId) {
        managers.add(userId + ":" + restaurantId);
    }

    @Override
    public long requireManager(long restaurantId) {
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        long userId = Long.parseLong(jwt.getSubject());
        if (!managers.contains(userId + ":" + restaurantId)) {
            throw new WebhookErrors.Forbidden("Нет прав на ресторан с id=" + restaurantId);
        }
        return userId;
    }
}
