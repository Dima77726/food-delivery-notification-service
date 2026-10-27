package com.dima.fooddelivery.notification.webhook;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Обработчик очереди доставок: одна попытка на сообщение.
 *
 * <pre>
 * аренда не взята          → доставку уже обрабатывают или она завершена — ничего не делаем
 * подписка выключена       → FAILED «подписка выключена»
 * 2xx                      → SUCCEEDED, счётчик провалов подписки обнуляется
 * 410 Gone                 → FAILED, подписка выключается сразу: получатель сказал «меня больше нет»
 * иначе, ступени остались  → попытка записана, сообщение — на следующую ступень лестницы
 * иначе                    → FAILED; после N проваленных доставок подряд подписка выключается
 * </pre>
 *
 * <p>Автовыключение — защита и наша, и получателя: подписка на мёртвый адрес иначе вечно
 * занимала бы обработчики и очередь повторов. Владелец видит причину и включает её кнопкой.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDispatcher {

    /** Больше таймаута запроса с запасом: аренда не истечёт, пока попытка ещё идёт. */
    static final Duration LEASE = Duration.ofSeconds(30);

    private final WebhookRepository repository;
    private final WebhookSender sender;
    private final WebhookTaskPublisher publisher;
    private final WebhookProperties properties;
    private final MeterRegistry meterRegistry;

    @RabbitListener(queues = WebhookTopology.DELIVERIES_QUEUE, concurrency = "${app.webhooks.concurrency:2}")
    public void onTask(Message message) {
        long deliveryId;
        try {
            deliveryId = Long.parseLong(new String(message.getBody(), StandardCharsets.UTF_8).strip());
        } catch (NumberFormatException exception) {
            log.warn("Вебхуки: непонятное сообщение в очереди пропущено");
            return;
        }
        attempt(deliveryId);
    }

    void attempt(long deliveryId) {
        if (!repository.tryLease(deliveryId, LEASE)) {
            return;
        }

        WebhookRepository.Delivery delivery = repository.findDelivery(deliveryId).orElse(null);
        WebhookRepository.Subscription subscription = delivery == null ? null
                : repository.findSubscription(delivery.subscriptionId()).orElse(null);
        if (delivery == null || subscription == null) {
            return;
        }
        if (!subscription.active()) {
            repository.markFailed(deliveryId, null, "Подписка выключена", false);
            count("skipped");
            return;
        }

        WebhookSender.Result result = sender.send(subscription, delivery);

        if (result.success()) {
            repository.markSucceeded(deliveryId, result.statusCode());
            repository.resetFailures(subscription.id());
            count("succeeded");
            return;
        }

        if (Integer.valueOf(410).equals(result.statusCode())) {
            repository.markFailed(deliveryId, 410, result.error(), true);
            repository.disable(subscription.id(), "Получатель ответил 410 Gone");
            log.warn("Вебхуки: подписка {} выключена — получатель ответил 410", subscription.id());
            count("failed");
            return;
        }

        int attemptsSoFar = delivery.attempts() + 1;
        if (attemptsSoFar < properties.maxAttempts()) {
            Duration delay = properties.retryDelays().get(attemptsSoFar - 1);
            repository.markRetry(deliveryId, result.statusCode(), result.error(), delay);
            publisher.retryAfter(deliveryId, delay);
            log.info("Вебхуки: доставка {} не удалась ({}), повтор через {}", deliveryId, result.error(), delay);
            count("retry");
            return;
        }

        repository.markFailed(deliveryId, result.statusCode(), result.error(), true);
        int failures = repository.incrementFailures(subscription.id());
        log.warn("Вебхуки: доставка {} провалена окончательно после {} попыток", deliveryId, attemptsSoFar);
        if (failures >= properties.disableAfterFailures()) {
            repository.disable(subscription.id(), "Выключена автоматически: " + failures + " доставок подряд не удались");
            log.warn("Вебхуки: подписка {} выключена после {} проваленных доставок подряд", subscription.id(), failures);
        }
        count("failed");
    }

    private void count(String outcome) {
        meterRegistry.counter("webhook.deliveries", "outcome", outcome).increment();
    }
}
