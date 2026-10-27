package com.dima.fooddelivery.notification.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Ставит доставки в очередь. Тело — идентификатор доставки, всё остальное обработчик возьмёт из
 * базы: база — источник правды, сообщение — только толчок. Потерянный толчок подберёт
 * {@link WebhookSweepJob}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookTaskPublisher {

    private final RabbitTemplate rabbitTemplate;

    /** Доставки, созданные в транзакции, — в очередь только после её коммита. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(DeliveriesCreated event) {
        event.deliveryIds().forEach(this::dispatch);
    }

    public void dispatch(long deliveryId) {
        send(WebhookTopology.DISPATCH_EXCHANGE, WebhookTopology.ROUTING_KEY, deliveryId);
    }

    /** На ступень лестницы: через её TTL доставка сама вернётся в рабочую очередь. */
    public void retryAfter(long deliveryId, Duration delay) {
        send("", WebhookTopology.retryQueue(delay), deliveryId);
    }

    private void send(String exchange, String routingKey, long deliveryId) {
        Message message = MessageBuilder.withBody(String.valueOf(deliveryId).getBytes(StandardCharsets.UTF_8))
                .setContentType("text/plain")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        try {
            rabbitTemplate.send(exchange, routingKey, message);
        } catch (RuntimeException exception) {
            // Доставка в базе со своим next_attempt_at — страховочный опрос поставит её заново.
            log.warn("Не удалось поставить доставку вебхука в очередь: deliveryId={}, reason={}", deliveryId, exception.getMessage());
        }
    }

    /** Внутреннее событие: в транзакции созданы доставки. */
    public record DeliveriesCreated(List<Long> deliveryIds) {
    }
}
