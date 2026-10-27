package com.dima.fooddelivery.notification.webhook;

import java.util.List;
import java.util.Locale;

/**
 * Типы событий вебхуков — закрытый список. Наружу уходит не внутреннее имя статуса, а свой
 * публичный контракт: {@code order.paid}, а не {@code PAID}. Внутренний статус можно переименовать,
 * а партнёрские интеграции на это не рассчитаны.
 */
public final class WebhookEvents {

    public static final String ALL = "*";
    public static final String TEST = "webhook.test";

    public static final List<String> ORDER_EVENTS = List.of(
            "order.created", "order.payment_pending", "order.paid", "order.accepted", "order.cooking",
            "order.ready_for_delivery", "order.in_delivery", "order.delivered", "order.canceled"
    );

    private WebhookEvents() {
    }

    /** {@code PAID} → {@code order.paid}. */
    public static String forStatus(String status) {
        return "order." + status.toLowerCase(Locale.ROOT);
    }

    public static boolean isKnown(String type) {
        return ALL.equals(type) || ORDER_EVENTS.contains(type);
    }
}
