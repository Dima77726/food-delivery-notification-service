package com.dima.fooddelivery.notification.web;

import com.dima.fooddelivery.notification.config.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Добавляет в лог и в ответ идентификатор пользователя — из проверенного токена.
 *
 * <p>Не {@code @Component}: фильтр обязан стоять внутри цепочки Spring Security, после
 * разбора токена, иначе контекст безопасности ещё пуст. Место задано в {@code SecurityConfig}.
 *
 * <p>{@code X-User-Id} из запроса не читается ни при каких обстоятельствах. Шлюз этот
 * заголовок срезает, но сервис не полагается на шлюз: до него можно достучаться в обход,
 * и тогда доверие к заголовку стало бы готовым способом назваться чужим именем.
 */
@RequiredArgsConstructor
public class UserIdMdcFilter extends OncePerRequestFilter {

    private final CurrentUser currentUser;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        currentUser.id().ifPresent(userId -> {
            MDC.put(RequestContext.USER_ID_MDC_KEY, String.valueOf(userId));
            response.setHeader(RequestContext.USER_ID_HEADER, String.valueOf(userId));
        });

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.USER_ID_MDC_KEY);
        }
    }
}
