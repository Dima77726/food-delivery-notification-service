package com.dima.fooddelivery.notification.domain;

/**
 * Уведомление записано в базу и ждёт отправки. Внутреннее событие процесса: по нему после
 * коммита ставится задача в RabbitMQ.
 */
public record NotificationEnqueued(
        Long notificationId,
        NotificationChannel channel,
        String type,
        String correlationId
) {
}
