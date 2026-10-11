package com.dima.fooddelivery.notification.api;

import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.domain.NotificationStatus;

import java.time.OffsetDateTime;

public record NotificationResponse(
        Long id,
        NotificationChannel channel,
        String type,
        String subject,
        String body,
        NotificationStatus status,
        Long orderId,
        OffsetDateTime createdAt,
        OffsetDateTime sentAt
) {
}
