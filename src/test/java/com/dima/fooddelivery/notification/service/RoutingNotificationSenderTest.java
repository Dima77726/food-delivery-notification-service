package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.domain.NotificationStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Выбор отправителя по каналу уведомления.
 *
 * <p>Главные свойства: канал со своим отправителем доходит именно до него, канал без отправителя
 * (выключенный флагом или без шлюза вовсе) уходит «в лог», а два отправителя на один канал —
 * ошибка при сборке, а не загадка в работе.
 */
class RoutingNotificationSenderTest {

    private final LoggingNotificationSender logging = mock(LoggingNotificationSender.class);

    @Test
    void shouldRouteByChannel() {
        ChannelNotificationSender email = channelSender(NotificationChannel.EMAIL);
        ChannelNotificationSender telegram = channelSender(NotificationChannel.TELEGRAM);
        RoutingNotificationSender router = new RoutingNotificationSender(List.of(email, telegram), logging);

        Notification toTelegram = notification(NotificationChannel.TELEGRAM);
        router.send(toTelegram);

        verify(telegram).send(toTelegram);
        verify(email, never()).send(toTelegram);
        verify(logging, never()).send(toTelegram);
    }

    /** SMS и PUSH шлюза не имеют, выключенный канал — тоже: все они остаются в логе, как и раньше. */
    @Test
    void shouldFallBackToLogForChannelsWithoutSender() {
        RoutingNotificationSender router = new RoutingNotificationSender(
                List.of(channelSender(NotificationChannel.EMAIL)), logging
        );

        Notification sms = notification(NotificationChannel.SMS);
        router.send(sms);

        verify(logging).send(sms);
    }

    @Test
    void shouldRefuseTwoSendersForOneChannel() {
        assertThrows(IllegalStateException.class, () -> new RoutingNotificationSender(
                List.of(channelSender(NotificationChannel.EMAIL), channelSender(NotificationChannel.EMAIL)), logging
        ));
    }

    private static ChannelNotificationSender channelSender(NotificationChannel channel) {
        ChannelNotificationSender sender = mock(ChannelNotificationSender.class);
        when(sender.channel()).thenReturn(channel);

        return sender;
    }

    private static Notification notification(NotificationChannel channel) {
        return new Notification(
                1L, 7L, channel, "ORDER_PAID", "Заказ №1", "Оплата прошла",
                NotificationStatus.PENDING, 1L, null, OffsetDateTime.now(), null
        );
    }
}
