package com.dima.fooddelivery.notification.messaging;

import java.util.Set;

/**
 * Приоритет уведомления в очереди канала.
 *
 * <p>Срочные — те, на которые клиент должен отреагировать: заказ отменён (в том числе потому,
 * что не прошла оплата). Им незачем стоять за сотней «заказ готовится», накопившихся, пока
 * лежал почтовый провайдер. Всё остальное — обычные. Когда появятся служебные письма
 * (код подтверждения, сброс пароля), они встанут сюда же с наивысшим приоритетом.
 */
public final class NotificationPriority {

    static final int URGENT = 8;
    static final int NORMAL = 4;

    private static final Set<String> URGENT_TYPES = Set.of(
            "ORDER_CANCELED",
            "ORDER_PAYMENT_FAILED"
    );

    private NotificationPriority() {
    }

    public static int of(String type) {
        return URGENT_TYPES.contains(type) ? URGENT : NORMAL;
    }
}
