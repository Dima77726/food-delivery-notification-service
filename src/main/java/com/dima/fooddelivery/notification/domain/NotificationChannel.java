package com.dima.fooddelivery.notification.domain;

public enum NotificationChannel {

    EMAIL, SMS, PUSH;

    public static NotificationChannel fromDbValue(String dbValue) {
        for (NotificationChannel channel : values()) {
            if (channel.name().equals(dbValue)) {
                return channel;
            }
        }

        throw new IllegalArgumentException("Неизвестный канал уведомления из базы данных: " + dbValue);
    }
}
