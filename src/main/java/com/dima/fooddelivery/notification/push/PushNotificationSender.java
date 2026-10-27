package com.dima.fooddelivery.notification.push;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.service.ChannelNotificationSender;
import com.dima.fooddelivery.notification.service.PermanentDeliveryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Канал PUSH: уведомление во все браузеры пользователя, подписанные на Web Push.
 *
 * <pre>
 * сервис ──POST, тело зашифровано (aes128gcm), Authorization: vapid…──▶ push-сервис браузера (FCM, Mozilla, Apple)
 *                                                                              │ держит до TTL, пока браузер офлайн
 *                                                                              ▼
 *                                                       service worker страницы (web/sw.js) показывает уведомление
 * </pre>
 *
 * <ul>
 *     <li>201/200 — принято push-сервисом;</li>
 *     <li>404/410 — подписка умерла (пользователь отозвал разрешение, браузер переустановлен):
 *     она удаляется, повторять незачем;</li>
 *     <li>429 и 5xx — временно: если ни один браузер не принял, исключение отправляет уведомление
 *     на повтор через очередь RabbitMQ, как у почты.</li>
 * </ul>
 */
@Slf4j
@Component
public class PushNotificationSender implements ChannelNotificationSender {

    private final PushSubscriptionRepository repository;
    private final WebPushProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public PushNotificationSender(PushSubscriptionRepository repository, WebPushProperties properties, ObjectMapper objectMapper) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.PUSH;
    }

    @Override
    public void send(Notification notification) {
        if (!properties.configured()) {
            throw new PermanentDeliveryException("Web Push выключен: нет ключей VAPID");
        }
        List<PushSubscriptionRepository.Subscription> subscriptions = repository.findByUser(notification.recipientId());
        if (subscriptions.isEmpty()) {
            throw new PermanentDeliveryException("У пользователя нет подписок Web Push");
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("title", notification.subject());
        message.put("body", notification.body());
        message.put("tag", notification.orderId() == null ? "notification" : "order-" + notification.orderId());
        message.put("url", notification.orderId() == null ? "/#/profile" : "/#/orders/" + notification.orderId());
        byte[] plaintext = objectMapper.writeValueAsBytes(message);

        int delivered = 0;
        int temporary = 0;
        for (PushSubscriptionRepository.Subscription subscription : subscriptions) {
            switch (sendOne(subscription, plaintext)) {
                case DELIVERED -> delivered++;
                case TEMPORARY -> temporary++;
                case GONE -> repository.delete(subscription.id());
            }
        }

        if (delivered == 0 && temporary > 0) {
            throw new IllegalStateException("Push-сервисы временно не приняли сообщение");
        }
        if (delivered == 0) {
            throw new PermanentDeliveryException("Ни одна подписка Web Push не действует");
        }
    }

    private Outcome sendOne(PushSubscriptionRepository.Subscription subscription, byte[] plaintext) {
        URI endpoint = URI.create(subscription.endpoint());
        String audience = endpoint.getScheme() + "://" + endpoint.getHost();
        long expires = Instant.now().plus(Duration.ofHours(12)).getEpochSecond();

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", String.valueOf(properties.ttl().toSeconds()))
                .header("Urgency", "normal")
                .header("Authorization", WebPushCrypto.vapidAuthorization(
                        audience, properties.subject(), properties.vapidPublicKey(), properties.vapidPrivateKey(), expires))
                .POST(HttpRequest.BodyPublishers.ofByteArray(
                        WebPushCrypto.encrypt(subscription.p256dh(), subscription.auth(), plaintext)))
                .build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status == 201 || status == 200) {
                repository.markSuccess(subscription.id());
                return Outcome.DELIVERED;
            }
            if (status == 404 || status == 410) {
                log.info("Подписка Web Push больше не действует (ответ {}), удаляем: id={}", status, subscription.id());
                return Outcome.GONE;
            }
            log.warn("Push-сервис ответил {}: subscriptionId={}", status, subscription.id());
            return status == 429 || status >= 500 ? Outcome.TEMPORARY : Outcome.GONE;
        } catch (IOException exception) {
            log.warn("Push-сервис недоступен: subscriptionId={}, reason={}", subscription.id(), exception.getMessage());
            return Outcome.TEMPORARY;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Outcome.TEMPORARY;
        }
    }

    private enum Outcome { DELIVERED, TEMPORARY, GONE }
}
