package com.dima.fooddelivery.notification.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Идентификатор текущего пользователя из токена.
 *
 * <p>Тот же контракт токена, что в монолите: числовой идентификатор лежит в {@code sub}.
 * Это соглашение между выпускающей стороной и всеми проверяющими, и меняется оно только
 * одновременно у всех.
 */
@Component
public class CurrentUser {

    public Optional<Long> id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }

        return Optional.of(Long.valueOf(jwt.getSubject()));
    }

    public Long requireId() {
        return id().orElseThrow(() -> new IllegalStateException(
                "Запрос дошёл до кода, требующего аутентификации, без пользователя в контексте"
        ));
    }
}
