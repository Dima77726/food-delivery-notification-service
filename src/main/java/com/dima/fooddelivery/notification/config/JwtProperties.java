package com.dima.fooddelivery.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры проверки JWT. Только проверки: токены выпускает identity-service.
 *
 * @param issuer    издатель, которого обязан назвать токен
 * @param jwkSetUri адрес JWKS сервиса пользователей — открытые ключи для проверки подписи
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String issuer,
        String jwkSetUri
) {
}
