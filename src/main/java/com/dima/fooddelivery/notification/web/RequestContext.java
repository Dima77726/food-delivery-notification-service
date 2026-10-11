package com.dima.fooddelivery.notification.web;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;

/**
 * Сквозная метка запроса — та же, что в монолите и в шлюзе.
 *
 * <p>Метка рождается на входе в систему, в шлюзе, и дальше едет заголовком: по HTTP
 * в сервис, через outbox и Kafka — в потребителя. Имя заголовка одно на всех и задано
 * в контракте событий; разойдись оно хоть у одного сервиса, цепочка рвётся молча.
 */
public final class RequestContext {

    public static final String CORRELATION_ID_HEADER = OrderEventTopics.CORRELATION_ID_HEADER;

    /** Только исходящий, см. {@link UserIdMdcFilter}. */
    public static final String USER_ID_HEADER = "X-User-Id";

    public static final String CORRELATION_ID_MDC_KEY = "correlationId";
    public static final String USER_ID_MDC_KEY = "userId";

    /** Значение приходит снаружи и попадает в лог и в базу: размер обязан быть предсказуем. */
    public static final int MAX_LENGTH = 64;

    private RequestContext() {
    }

    /**
     * Оставляет только буквы, цифры, дефис и подчёркивание. Чужая строка попадает в лог,
     * и без фильтрации в неё можно протолкнуть перевод строки и подделать запись.
     *
     * @return очищенная метка или {@code null}, если осмысленного ничего не осталось
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String sanitized = raw.replaceAll("[^A-Za-z0-9\\-_]", "");

        if (sanitized.isEmpty()) {
            return null;
        }

        return sanitized.length() > MAX_LENGTH ? sanitized.substring(0, MAX_LENGTH) : sanitized;
    }
}
