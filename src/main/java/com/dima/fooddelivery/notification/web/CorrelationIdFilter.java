package com.dima.fooddelivery.notification.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Принимает сквозную метку от шлюза или генерирует свою.
 *
 * <p>Своя генерация — страховка, а не основной путь: в нормальной работе метку ставит
 * шлюз. Но сервис доступен и в обход него — из IDE, из тестов, из соседнего сервиса, —
 * и запрос без метки не должен оставлять в логе пустое место.
 *
 * <p>Метка снимается в {@code finally}: MDC живёт в ThreadLocal, а поток вернётся в пул
 * и достанется следующему запросу.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String correlationId = RequestContext.sanitize(request.getHeader(RequestContext.CORRELATION_ID_HEADER));

        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, correlationId);
        response.setHeader(RequestContext.CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
        }
    }
}
