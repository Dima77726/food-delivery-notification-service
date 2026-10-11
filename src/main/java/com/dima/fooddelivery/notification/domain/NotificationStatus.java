package com.dima.fooddelivery.notification.domain;

public enum NotificationStatus {

    PENDING, SENT, FAILED;

    public static NotificationStatus fromDbValue(String dbValue) {
        for (NotificationStatus status : values()) {
            if (status.name().equals(dbValue)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Неизвестный статус уведомления из базы данных: " + dbValue);
    }
}
