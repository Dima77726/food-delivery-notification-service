package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.domain.NotificationStatus;
import com.dima.fooddelivery.notification.grpc.UserContactInfo;
import com.dima.fooddelivery.notification.grpc.UserDirectoryClient;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Отправка почты без сети: контакт и SMTP подменены.
 *
 * <p>Главное, что здесь проверяется, — граница между постоянной ошибкой (получателя нет, он
 * заблокирован) и временной (SMTP или identity-service недоступны). От неё зависит, будет ли
 * очередь повторять безнадёжное или бросит письмо, которое ушло бы со второй попытки.
 */
class EmailNotificationSenderTest {

    private final UserDirectoryClient directory = mock(UserDirectoryClient.class);
    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final LoggingNotificationSender fallback = mock(LoggingNotificationSender.class);

    private final EmailNotificationSender sender =
            new EmailNotificationSender(directory, mailSender, fallback, "no-reply@food-delivery.local");

    @Test
    void shouldSendRussianMailToAddressFromDirectory() throws Exception {
        when(directory.findContact(7L)).thenReturn(Optional.of(new UserContactInfo(7L, "anna@example.test", "Анна", true)));
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        sender.send(email(7L, "Заказ принят", "Ваш заказ №48 принят рестораном."));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        MimeMessage mime = sent.getValue();
        mime.saveChanges();

        assertAll(
                () -> assertEquals("anna@example.test", mime.getAllRecipients()[0].toString()),
                () -> assertEquals("no-reply@food-delivery.local", mime.getFrom()[0].toString()),
                () -> assertEquals("Заказ принят", mime.getSubject(), "тема по-русски доходит без искажений"),
                () -> assertTrue(String.valueOf(mime.getContent()).contains("Здравствуйте, Анна!")),
                () -> assertTrue(String.valueOf(mime.getContent()).contains("Ваш заказ №48 принят рестораном."))
        );
    }

    @Test
    void shouldOmitGreetingWhenNameIsEmpty() throws Exception {
        when(directory.findContact(7L)).thenReturn(Optional.of(new UserContactInfo(7L, "anna@example.test", "", true)));
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        sender.send(email(7L, "Тема", "Текст"));

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());

        assertEquals("Текст", String.valueOf(sent.getValue().getContent()));
    }

    /** Пользователя нет — ждать нечего: повтор из очереди дал бы тот же ответ. */
    @Test
    void shouldFailPermanentlyForUnknownRecipient() {
        when(directory.findContact(404L)).thenReturn(Optional.empty());

        assertThrows(PermanentDeliveryException.class, () -> sender.send(email(404L, "Тема", "Текст")));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void shouldFailPermanentlyForBlockedRecipient() {
        when(directory.findContact(8L)).thenReturn(Optional.of(new UserContactInfo(8L, "blocked@example.test", "Борис", false)));

        assertThrows(PermanentDeliveryException.class, () -> sender.send(email(8L, "Тема", "Текст")));
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void shouldFailPermanentlyForRecipientWithoutAddress() {
        when(directory.findContact(9L)).thenReturn(Optional.of(new UserContactInfo(9L, "  ", "Вера", true)));

        assertThrows(PermanentDeliveryException.class, () -> sender.send(email(9L, "Тема", "Текст")));
    }

    /** Недоступный identity-service — не «получателя нет»: исключение уходит наружу, и очередь повторит. */
    @Test
    void shouldLetDirectoryOutageTriggerRetry() {
        when(directory.findContact(7L)).thenThrow(
                new UserDirectoryClient.DirectoryUnavailableException("identity лежит", new RuntimeException())
        );

        RuntimeException error = assertThrows(RuntimeException.class, () -> sender.send(email(7L, "Тема", "Текст")));

        assertTrue(!(error instanceof PermanentDeliveryException), "временный сбой не должен выглядеть постоянным");
    }

    @Test
    void shouldLetSmtpOutageTriggerRetry() {
        when(directory.findContact(7L)).thenReturn(Optional.of(new UserContactInfo(7L, "anna@example.test", "Анна", true)));
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        org.mockito.Mockito.doThrow(new MailSendException("connection refused")).when(mailSender).send(any(MimeMessage.class));

        RuntimeException error = assertThrows(MailSendException.class, () -> sender.send(email(7L, "Тема", "Текст")));

        assertTrue(!(error instanceof PermanentDeliveryException));
    }

    /** У SMS и PUSH шлюза нет: они остаются в логе, и в директорию за адресом не ходят. */
    @Test
    void shouldLeaveOtherChannelsToTheLog() {
        Notification sms = notification(7L, NotificationChannel.SMS, "Тема", "Текст");

        sender.send(sms);

        verify(fallback).send(sms);
        verify(directory, never()).findContact(any());
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    private static Notification email(Long recipientId, String subject, String body) {
        return notification(recipientId, NotificationChannel.EMAIL, subject, body);
    }

    private static Notification notification(Long recipientId, NotificationChannel channel, String subject, String body) {
        return new Notification(
                1L, recipientId, channel, "ORDER_ACCEPTED", subject, body,
                NotificationStatus.PENDING, 48L, null, OffsetDateTime.now(), null
        );
    }
}
