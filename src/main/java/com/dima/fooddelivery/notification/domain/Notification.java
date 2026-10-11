package com.dima.fooddelivery.notification.domain;

import java.time.OffsetDateTime;

public record Notification(
        Long id,
        Long recipientId,
        NotificationChannel channel,
        String type,
        String subject,
        String body,
        NotificationStatus status,
        Long orderId,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime sentAt
) {
}
