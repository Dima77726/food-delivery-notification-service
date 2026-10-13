package com.dima.fooddelivery.notification.telegram;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.service.ChannelNotificationSender;
import com.dima.fooddelivery.notification.service.PermanentDeliveryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Доставка по каналу TELEGRAM: сообщение в чат, привязанный пользователем.
 *
 * <p>Чат берётся из привязки в момент отправки, а не из уведомления: привязку могли снять между
 * постановкой в очередь и отправкой. Нет привязки — постоянная ошибка
 * ({@link PermanentDeliveryException}), повторять нечего.
 *
 * <p><b>Что считается постоянной ошибкой.</b> Бота заблокировали (403) или чата нет (400
 * «chat not found»): привязка снимается сама — писать в такой чат больше не получится, а
 * хранить её значило бы пытаться снова на каждом заказе. Остальное — 429 (слишком часто), 5xx,
 * сеть, таймаут — временное, и очередь повторит.
 *
 * <p>Текст — обычный, без разметки: тема и тело приходят из событий и могут содержать любые
 * символы, а разметка Telegram требует их экранировать, и ошибка экранирования роняла бы
 * сообщение целиком.
 *
 * <p>В лог идёт номер пользователя, а не номер чата: по нему чат не найти, но уведомление найти можно.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class TelegramNotificationSender implements ChannelNotificationSender {

    /** Предел длины сообщения Bot API; длиннее — отказ 400, который мы не смогли бы отличить от других 400. */
    static final int MAX_TEXT_LENGTH = 4096;

    private final TelegramLinkService links;
    private final TelegramClient client;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.TELEGRAM;
    }

    @Override
    public void send(Notification notification) {
        long chatId = links.chatIdOf(notification.recipientId())
                .orElseThrow(() -> new PermanentDeliveryException(
                        "Telegram не подключён: userId=" + notification.recipientId()
                ));

        try {
            client.sendMessage(chatId, textOf(notification));
        } catch (TelegramClient.TelegramApiException exception) {
            if (exception.isChatUnavailable()) {
                links.unlinkChat(chatId);

                throw new PermanentDeliveryException(
                        "Чат Telegram недоступен, привязка снята: userId=" + notification.recipientId(), exception
                );
            }

            // 429, 5xx, сеть: исключение уходит наружу, очередь повторит. Номер чата в сообщение не попадает.
            throw exception;
        }

        log.info("Сообщение отправлено в Telegram: notificationId={}, userId={}",
                notification.id(), notification.recipientId());
    }

    static String textOf(Notification notification) {
        String text = notification.subject() + "\n" + notification.body();

        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH - 1) + "…";
    }
}
