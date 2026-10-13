package com.dima.fooddelivery.notification.telegram;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.domain.NotificationStatus;
import com.dima.fooddelivery.notification.service.PermanentDeliveryException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Отправка в Telegram без сети: привязки и Bot API подменены.
 *
 * <p>Как и у почты, главное — граница между постоянной ошибкой (чат недоступен — привязка снимается,
 * повторять нечего) и временной (слишком часто, 5xx, сеть — очередь повторит).
 */
class TelegramNotificationSenderTest {

    private final TelegramLinkService links = mock(TelegramLinkService.class);
    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramNotificationSender sender = new TelegramNotificationSender(links, client);

    @Test
    void shouldSendSubjectAndBodyToLinkedChat() {
        when(links.chatIdOf(7L)).thenReturn(Optional.of(555L));

        sender.send(notification(7L, "Заказ №82", "Ресторан принял ваш заказ"));

        verify(client).sendMessage(555L, "Заказ №82\nРесторан принял ваш заказ");
    }

    /** Привязку могли снять между постановкой в очередь и отправкой: писать некуда, повтор ничего не изменит. */
    @Test
    void shouldFailPermanentlyWhenNotLinked() {
        when(links.chatIdOf(7L)).thenReturn(Optional.empty());

        assertThrows(PermanentDeliveryException.class, () -> sender.send(notification(7L, "Тема", "Текст")));
        verify(client, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void shouldUnlinkAndFailPermanentlyWhenBotIsBlocked() {
        when(links.chatIdOf(7L)).thenReturn(Optional.of(555L));
        doThrow(new TelegramClient.TelegramApiException(403, "Telegram ответил 403: Forbidden: bot was blocked by the user"))
                .when(client).sendMessage(anyLong(), anyString());

        assertThrows(PermanentDeliveryException.class, () -> sender.send(notification(7L, "Тема", "Текст")));

        // Писать в такой чат больше нельзя: привязка снимается, чтобы не пытаться на каждом заказе.
        verify(links).unlinkChat(555L);
    }

    @Test
    void shouldLetRateLimitAndOutagesTriggerRetryWithoutUnlinking() {
        when(links.chatIdOf(7L)).thenReturn(Optional.of(555L));
        TelegramClient.TelegramApiException rateLimit =
                new TelegramClient.TelegramApiException(429, "Telegram ответил 429: Too Many Requests", 7, null);
        doThrow(rateLimit).when(client).sendMessage(anyLong(), anyString());

        TelegramClient.TelegramApiException thrown = assertThrows(
                TelegramClient.TelegramApiException.class, () -> sender.send(notification(7L, "Тема", "Текст")));

        // Временный сбой уходит наружу как есть и не является PermanentDeliveryException: очередь повторит.
        assertSame(rateLimit, thrown);
        verify(links, never()).unlinkChat(anyLong());
    }

    /** Лимит Bot API — 4096 символов; длиннее — отказ 400, который не отличить от других 400. */
    @Test
    void shouldTruncateTextToBotApiLimit() {
        when(links.chatIdOf(7L)).thenReturn(Optional.of(555L));

        sender.send(notification(7L, "Тема", "я".repeat(5000)));

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(client).sendMessage(anyLong(), text.capture());
        assertAll(
                () -> assertEquals(TelegramNotificationSender.MAX_TEXT_LENGTH, text.getValue().length()),
                () -> assertTrue(text.getValue().endsWith("…"))
        );
    }

    @Test
    void shouldServeTelegramChannel() {
        assertEquals(NotificationChannel.TELEGRAM, sender.channel());
    }

    private static Notification notification(Long recipientId, String subject, String body) {
        return new Notification(
                1L, recipientId, NotificationChannel.TELEGRAM, "ORDER_ACCEPTED", subject, body,
                NotificationStatus.PENDING, 82L, null, OffsetDateTime.now(), null
        );
    }
}
