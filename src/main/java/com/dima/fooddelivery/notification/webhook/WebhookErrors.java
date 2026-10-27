package com.dima.fooddelivery.notification.webhook;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ошибки API вебхуков в том же формате, что у остальных сервисов: {@code {status, error, message, path}}.
 * Остальные ручки сервиса (лента уведомлений) ошибок предметной области не бросают.
 */
@Slf4j
@RestControllerAdvice(assignableTypes = WebhookController.class)
public class WebhookErrors {

    public static class NotFound extends RuntimeException {
        public NotFound(String message) { super(message); }
    }

    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) { super(message); }
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }

    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) { super(message); }
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<Map<String, Object>> notFound(NotFound exception, HttpServletRequest request) {
        return body(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler(Forbidden.class)
    ResponseEntity<Map<String, Object>> forbidden(Forbidden exception, HttpServletRequest request) {
        log.warn("Отказ в доступе к вебхукам: path={}, message={}", request.getRequestURI(), exception.getMessage());
        return body(HttpStatus.FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler(Conflict.class)
    ResponseEntity<Map<String, Object>> conflict(Conflict exception, HttpServletRequest request) {
        return body(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(Unavailable.class)
    ResponseEntity<Map<String, Object>> unavailable(Unavailable exception, HttpServletRequest request) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request);
    }

    @ExceptionHandler(WebhookUrlGuard.InvalidWebhookUrlException.class)
    ResponseEntity<Map<String, Object>> badUrl(WebhookUrlGuard.InvalidWebhookUrlException exception, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Map<String, Object>> invalidParameter(HandlerMethodValidationException exception, HttpServletRequest request) {
        return body(HttpStatus.BAD_REQUEST, "Неверные параметры запроса", request);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message,
                "path", request.getRequestURI()));
    }
}
