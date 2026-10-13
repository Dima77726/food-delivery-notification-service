package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Единственная точка, через которую {@link NotificationService} отправляет уведомления: выбирает
 * реализацию по каналу уведомления.
 *
 * <p>Это единственный бин {@code NotificationSender} в приложении. Канал без своего отправителя — либо выключенный флагом (почта, Telegram), либо такой,
 * у которого шлюза нет вовсе (SMS, PUSH), — уходит «в лог», как и раньше. Так включение канала
 * не требует правок ни в сервисе, ни в базе, а выключенный канал не ломает доставку остальных.
 *
 * <p>Два отправителя на один канал — ошибка конфигурации, и приложение не стартует: иначе одно
 * из двух молча перестало бы работать.
 */
@Component
public class RoutingNotificationSender implements NotificationService.NotificationSender {

    private final Map<NotificationChannel, ChannelNotificationSender> senders = new EnumMap<>(NotificationChannel.class);
    private final LoggingNotificationSender fallback;

    public RoutingNotificationSender(List<ChannelNotificationSender> channelSenders, LoggingNotificationSender fallback) {
        this.fallback = fallback;

        for (ChannelNotificationSender sender : channelSenders) {
            ChannelNotificationSender previous = senders.put(sender.channel(), sender);

            if (previous != null) {
                throw new IllegalStateException(
                        "Два отправителя на канал " + sender.channel() + ": "
                                + previous.getClass().getSimpleName() + " и " + sender.getClass().getSimpleName()
                );
            }
        }
    }

    @Override
    public void send(Notification notification) {
        ChannelNotificationSender sender = senders.get(notification.channel());

        if (sender != null) {
            sender.send(notification);
        } else {
            fallback.send(notification);
        }
    }
}
