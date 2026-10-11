package com.dima.fooddelivery.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Параметры проверки JWT.
 *
 * <p>Здесь нет срока жизни токена и прочего, что нужно для выпуска: сервис токены
 * только проверяет. Выпускает их монолит, а в будущем — отдельный identity-service.
 */
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        String secret,
        String issuer
) {
}
