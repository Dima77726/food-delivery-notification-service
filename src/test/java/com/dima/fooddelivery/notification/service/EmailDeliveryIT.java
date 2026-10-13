package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.domain.NotificationStatus;
import com.dima.fooddelivery.notification.grpc.UserContactInfo;
import com.dima.fooddelivery.notification.grpc.UserDirectoryClient;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Письмо по-настоящему: настоящий SMTP-сервер (Mailpit в контейнере) и настоящий JavaMailSender.
 *
 * <p>Юнит-тест отправителя проверяет логику, но не то, что приходит на провод. Здесь проверяется
 * именно оно: кириллица в теме и тексте после кодирования MIME доходит читаемой, адресат и
 * отправитель те, что нужно. Контакт подменён — identity-service тут не участвует.
 */
@Testcontainers
class EmailDeliveryIT {

    private static final int SMTP_PORT = 1025;
    private static final int API_PORT = 8025;

    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.21")
            .withExposedPorts(SMTP_PORT, API_PORT)
            .waitingFor(Wait.forHttp("/api/v1/info").forPort(API_PORT));

    @Test
    void shouldDeliverRussianMailThroughRealSmtp() throws Exception {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(MAILPIT.getHost());
        mailSender.setPort(MAILPIT.getMappedPort(SMTP_PORT));
        mailSender.setDefaultEncoding("UTF-8");
        Properties properties = mailSender.getJavaMailProperties();
        properties.put("mail.smtp.connectiontimeout", "3000");
        properties.put("mail.smtp.timeout", "10000");

        UserDirectoryClient directory = userId ->
                Optional.of(new UserContactInfo(userId, "anna@example.test", "Анна", true));
        EmailNotificationSender sender = new EmailNotificationSender(directory, mailSender, "no-reply@food-delivery.local");

        sender.send(new Notification(
                1L, 7L, NotificationChannel.EMAIL, "ORDER_ACCEPTED",
                "Заказ принят", "Ваш заказ №48 принят рестораном.",
                NotificationStatus.PENDING, 48L, null, OffsetDateTime.now(), null
        ));

        JsonNode message = firstMessage();

        assertAll(
                () -> assertEquals("anna@example.test", message.path("To").get(0).path("Address").asString()),
                () -> assertEquals("no-reply@food-delivery.local", message.path("From").path("Address").asString()),
                () -> assertEquals("Заказ принят", message.path("Subject").asString()),
                () -> assertTrue(message.path("Snippet").asString().contains("Здравствуйте, Анна!")),
                () -> assertTrue(message.path("Snippet").asString().contains("№48"))
        );
    }

    /** Список писем из HTTP API Mailpit — то, что увидел бы человек в его интерфейсе. */
    private JsonNode firstMessage() throws Exception {
        URI uri = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(API_PORT) + "/api/v1/messages");
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());

        JsonNode messages = JsonMapper.builder().build().readTree(response.body()).path("messages");
        assertEquals(1, messages.size(), "в ящике должно быть ровно одно письмо: " + response.body());

        return messages.get(0);
    }
}
