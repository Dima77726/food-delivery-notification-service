package com.dima.fooddelivery.notification.domain;

/**
 * Уведомление записано в базу и ждёт отправки. Внутреннее событие процесса: по нему после
 * коммита ставится задача в RabbitMQ и будятся долгие запросы получателя.
 */
public record NotificationEnqueued(
        Long notificationId,
        Long recipientId,
        NotificationChannel channel,
        String type,
        String correlationId
) {
}
