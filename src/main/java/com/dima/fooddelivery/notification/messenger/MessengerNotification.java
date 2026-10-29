package com.dima.fooddelivery.notification.messenger;

import java.time.OffsetDateTime;

/**
 * Задача от мессенджера: кому, что и куда вести по ссылке. Текст уже собран мессенджером с учётом настройки
 * показа пользователя — здесь его не дополняют ни именами, ни содержимым переписки.
 *
 * @param key  ключ идемпотентности (одно уведомление — один ключ)
 * @param kind MESSAGES, MENTION, INCOMING_CALL, MISSED_CALL
 */
public record MessengerNotification(String key, long userId, String kind, String title, String body, String link, OffsetDateTime createdAt) {
}
