package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.grpc.UserContactInfo;
import com.dima.fooddelivery.notification.grpc.UserDirectoryClient;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailParseException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Настоящая доставка по каналу EMAIL: адрес берётся у identity-service, письмо уходит по SMTP.
 *
 * <p>Включается {@code app.notification.email.enabled=true}; без него канал EMAIL по-прежнему
 * уходит «в лог» ({@link LoggingNotificationSender}) — выбор делает {@link RoutingNotificationSender}.
 * SMS и PUSH остаются в логе при любых настройках: у них нет шлюза.
 *
 * <p><b>Адрес не хранится здесь.</b> Событие заказа его не содержит (его читают многие
 * потребители, а адрес — персональные данные), поэтому контакт спрашивается в момент отправки.
 * Цена — зависимость отправки от identity-service; её смягчают повторы очереди и предохранитель
 * на канале. Альтернатива — копия адресов в этом сервисе, наполняемая событиями пользователей:
 * независимее, но это второй источник персональных данных, который нужно синхронизировать и
 * чистить.
 *
 * <p><b>Что считается постоянной ошибкой</b> ({@link PermanentDeliveryException}): пользователя нет,
 * он заблокирован, адрес пуст или не разбирается. Недоступный SMTP или identity-service — временная:
 * исключение уходит наружу, и очередь повторит.
 *
 * <p><b>Доставка «хотя бы один раз».</b> Отправка идёт внутри транзакции доставки: если SMTP принял
 * письмо, а коммит не удался, уведомление уйдёт повторно. Окно мало, а лечится оно только
 * идемпотентностью на стороне получателя — у почты её нет. «Ровно один раз» невозможно без
 * распределённой транзакции, и выбор сделан в пользу повтора, а не потери.
 *
 * <p>В лог адрес получателя не пишется — только номер пользователя.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.email.enabled", havingValue = "true")
public class EmailNotificationSender implements ChannelNotificationSender {

    private final UserDirectoryClient directory;
    private final JavaMailSender mailSender;
    private final String from;

    public EmailNotificationSender(
            UserDirectoryClient directory,
            JavaMailSender mailSender,
            @Value("${app.notification.email.from:no-reply@food-delivery.local}") String from
    ) {
        this.directory = directory;
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    @Override
    public void send(Notification notification) {
        UserContactInfo contact = directory.findContact(notification.recipientId())
                .orElseThrow(() -> new PermanentDeliveryException(
                        "Получатель не найден: userId=" + notification.recipientId()
                ));

        if (!contact.enabled()) {
            throw new PermanentDeliveryException("Получатель заблокирован: userId=" + notification.recipientId());
        }

        if (contact.email() == null || contact.email().isBlank()) {
            throw new PermanentDeliveryException("У получателя нет адреса: userId=" + notification.recipientId());
        }

        mailSender.send(compose(notification, contact));

        log.info("Письмо отправлено: notificationId={}, userId={}", notification.id(), notification.recipientId());
    }

    private MimeMessage compose(Notification notification, UserContactInfo contact) {
        MimeMessage message = mailSender.createMimeMessage();

        try {
            // UTF-8 явно: тема и текст по-русски, а кодировка по умолчанию зависит от машины.
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(contact.email());
            helper.setSubject(notification.subject());
            helper.setText(greeting(contact) + notification.body(), false);
        } catch (MessagingException exception) {
            // Адрес или заголовок не разбираются: повтор даст то же самое.
            throw new PermanentDeliveryException("Письмо не собрано: userId=" + notification.recipientId(), exception);
        } catch (MailParseException exception) {
            throw new PermanentDeliveryException(
                    "Адрес получателя не разбирается: userId=" + notification.recipientId(), exception
            );
        }

        return message;
    }

    private static String greeting(UserContactInfo contact) {
        if (contact.fullName() == null || contact.fullName().isBlank()) {
            return "";
        }

        return "Здравствуйте, " + contact.fullName() + "!\n\n";
    }
}
