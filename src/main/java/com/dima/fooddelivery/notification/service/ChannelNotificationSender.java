package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;

/**
 * Отправитель одного канала. Реализации ({@link EmailNotificationSender}, отправитель Telegram)
 * собирает {@link RoutingNotificationSender}: канал — их единственный общий признак, остальное
 * (SMTP, Bot API) у каждого своё.
 *
 * <p>Намеренно не наследует {@code NotificationSender}: бином этого типа в приложении должен быть
 * один — маршрутизатор. Появись второй, внедрение в {@link NotificationService} стало бы
 * неоднозначным, а тесты, подменяющие отправителя своим, пришлось бы перебивать «главным» бином.
 *
 * <p>Отправитель отвечает только за свой канал и не проверяет чужие: выбор по каналу уже сделан
 * маршрутизатором, и дублировать его здесь значило бы держать правило в двух местах.
 */
public interface ChannelNotificationSender {

    NotificationChannel channel();

    /**
     * @throws PermanentDeliveryException если доставить нельзя и повтор не поможет; любое другое
     *                                    исключение значит «временно» и уходит на повтор
     */
    void send(Notification notification);
}
