package com.dima.fooddelivery.notification.webhook;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import com.dima.fooddelivery.contracts.order.OrderIntegrationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Событие заказа из Kafka → доставки всем подписчикам ресторана (fan-out).
 *
 * <p><b>Своя группа потребителя.</b> Те же события читает и рассылка уведомлений клиентам, но
 * в другой группе — у каждой свои офсеты. Медленный или упавший партнёрский контур не задерживает
 * письма клиентам, и наоборот.
 *
 * <p><b>Тело — снимок.</b> JSON собирается здесь один раз и хранится в доставке: все попытки
 * и ручные повторы шлют байт в байт то же тело, а подпись каждой попытки сходится с ним.
 *
 * <p>Повтор сообщения Kafka безвреден: пара (подписка, событие) уникальна, вторая вставка
 * ничего не создаёт.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookFanout {

    private final WebhookRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @KafkaListener(topics = OrderEventTopics.ORDER_EVENTS, groupId = "${app.webhooks.consumer-group:notification-webhooks}")
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        OrderIntegrationEvent event = objectMapper.readValue(record.value(), OrderIntegrationEvent.class);
        if (event.restaurantId() == null || event.newStatus() == null) {
            return;
        }

        String type = WebhookEvents.forStatus(event.newStatus());
        ObjectNode data = objectMapper.createObjectNode()
                .put("orderId", event.orderId())
                .put("restaurantId", event.restaurantId())
                .put("status", event.newStatus())
                .put("previousStatus", event.previousStatus())
                .put("totalAmount", event.totalAmount());

        fanOut(event.restaurantId(), event.eventId(), type, data, event.occurredAt());
    }

    /** Пробное событие одной подписке — тот же путь, что у настоящих. */
    public long sendTest(WebhookRepository.Subscription subscription) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("message", "Проверка вебхука: если вы это видите, подпись и адрес настроены верно")
                .put("subscriptionId", subscription.id());
        String body = envelope(WebhookEvents.TEST, data, OffsetDateTime.now());
        long id = transactionTemplate.execute(status -> {
            long deliveryId = repository.insertDelivery(subscription.id(), UUID.randomUUID(), WebhookEvents.TEST, body).orElseThrow();
            eventPublisher.publishEvent(new WebhookTaskPublisher.DeliveriesCreated(List.of(deliveryId)));
            return deliveryId;
        });
        return id;
    }

    private void fanOut(long restaurantId, UUID eventId, String type, ObjectNode data, OffsetDateTime occurredAt) {
        String body = envelope(type, data, occurredAt);

        transactionTemplate.executeWithoutResult(status -> {
            List<Long> created = new ArrayList<>();
            for (WebhookRepository.Subscription subscription : repository.findActiveFor(restaurantId, type)) {
                repository.insertDelivery(subscription.id(), eventId, type, body).ifPresent(created::add);
            }
            if (!created.isEmpty()) {
                eventPublisher.publishEvent(new WebhookTaskPublisher.DeliveriesCreated(created));
                log.info("Вебхуки: событие {} заказа → {} доставок", type, created.size());
            }
        });
    }

    /** Конверт Standard Webhooks: {@code {type, timestamp, data}}. */
    private String envelope(String type, ObjectNode data, OffsetDateTime occurredAt) {
        ObjectNode root = objectMapper.createObjectNode()
                .put("type", type)
                .put("timestamp", (occurredAt == null ? OffsetDateTime.now() : occurredAt).toString());
        root.set("data", data);
        return objectMapper.writeValueAsString(root);
    }
}
