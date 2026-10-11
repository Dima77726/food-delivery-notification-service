package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Отправка «в лог».
 *
 * <p>Настоящего SMTP или SMS-шлюза в проекте нет, и подключать их сейчас рано. Важно, что
 * механика вокруг — очередь, статусы, повторные попытки — уже боевая: замена этой реализации
 * на реальную не потребует правок ни в сервисе, ни в базе.
 */
@Slf4j
@Component
public class LoggingNotificationSender implements NotificationService.NotificationSender {

    @Override
    public void send(Notification notification) {
        log.info(
                "[{}] Уведомление пользователю {}: {} | {}",
                notification.channel(),
                notification.recipientId(),
                notification.subject(),
                notification.body()
        );
    }
}
