package com.dima.fooddelivery.notification.api;

import com.dima.fooddelivery.notification.domain.Notification;

import java.util.List;

public final class NotificationResponseMapper {

    public static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.id(),
                notification.channel(),
                notification.type(),
                notification.subject(),
                notification.body(),
                notification.status(),
                notification.orderId(),
                notification.createdAt(),
                notification.sentAt()
        );
    }

    public static List<NotificationResponse> toResponses(List<Notification> notifications) {
        return notifications.stream().map(NotificationResponseMapper::toResponse).toList();
    }

    private NotificationResponseMapper() {
    }
}
