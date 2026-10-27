package com.dima.fooddelivery.notification.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Управление подписками ресторана. Права на ресторан проверяет {@link RestaurantAccess}
 * перед каждым действием; подписка чужого ресторана неотличима от несуществующей.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    static final int MAX_SUBSCRIPTIONS = 10;
    static final Duration ROTATION_GRACE = Duration.ofHours(24);

    private final WebhookRepository repository;
    private final RestaurantAccess access;
    private final WebhookUrlGuard urlGuard;
    private final SecretCipher cipher;
    private final WebhookFanout fanout;
    private final WebhookTaskPublisher publisher;

    public Created create(long restaurantId, String url, List<String> eventTypes, String description) {
        long userId = access.requireManager(restaurantId);
        if (!cipher.isConfigured()) {
            throw new WebhookErrors.Unavailable("Вебхуки выключены: не задан WEBHOOK_SECRET_KEY");
        }
        urlGuard.check(url);

        List<String> types = List.copyOf(new LinkedHashSet<>(eventTypes));
        types.stream().filter(type -> !WebhookEvents.isKnown(type)).findFirst().ifPresent(type -> {
            throw new WebhookUrlGuard.InvalidWebhookUrlException("Неизвестный тип события: " + type
                    + ". Допустимы: " + String.join(", ", WebhookEvents.ORDER_EVENTS) + " или *");
        });
        if (repository.findByRestaurant(restaurantId).size() >= MAX_SUBSCRIPTIONS) {
            throw new WebhookErrors.Conflict("Не больше " + MAX_SUBSCRIPTIONS + " подписок на ресторан");
        }

        String secret = WebhookSigner.newSecret();
        long id = repository.insertSubscription(restaurantId, userId, url.strip(), description, types, cipher.encrypt(secret));
        log.info("Вебхук создан: id={}, restaurantId={}, types={}", id, restaurantId, types);

        return new Created(require(restaurantId, id), secret);
    }

    public List<WebhookRepository.Subscription> list(long restaurantId) {
        access.requireManager(restaurantId);
        return repository.findByRestaurant(restaurantId);
    }

    public void delete(long restaurantId, long id) {
        access.requireManager(restaurantId);
        if (!repository.deleteSubscription(id, restaurantId)) {
            throw new WebhookErrors.NotFound("Подписка с id=" + id + " не найдена");
        }
    }

    /** Новый секрет — показывается один раз; старый действует ещё сутки. */
    public Created rotateSecret(long restaurantId, long id) {
        access.requireManager(restaurantId);
        require(restaurantId, id);
        String secret = WebhookSigner.newSecret();
        repository.rotateSecret(id, cipher.encrypt(secret), ROTATION_GRACE);
        return new Created(require(restaurantId, id), secret);
    }

    public WebhookRepository.Subscription enable(long restaurantId, long id) {
        access.requireManager(restaurantId);
        require(restaurantId, id);
        repository.enable(id);
        return require(restaurantId, id);
    }

    public long sendTest(long restaurantId, long id) {
        access.requireManager(restaurantId);
        WebhookRepository.Subscription subscription = require(restaurantId, id);
        if (!subscription.active()) {
            throw new WebhookErrors.Conflict("Подписка выключена: включите её, чтобы отправить пробное событие");
        }
        return fanout.sendTest(subscription);
    }

    public List<WebhookRepository.Delivery> deliveries(long restaurantId, long id, int limit) {
        access.requireManager(restaurantId);
        require(restaurantId, id);
        return repository.findDeliveries(id, limit);
    }

    public void redeliver(long restaurantId, long id, long deliveryId) {
        access.requireManager(restaurantId);
        require(restaurantId, id);
        if (!repository.resetForRedelivery(deliveryId, id)) {
            throw new WebhookErrors.NotFound("Доставка с id=" + deliveryId + " не найдена");
        }
        publisher.dispatch(deliveryId);
    }

    private WebhookRepository.Subscription require(long restaurantId, long id) {
        return repository.findSubscription(id)
                .filter(subscription -> subscription.restaurantId() == restaurantId)
                .orElseThrow(() -> new WebhookErrors.NotFound("Подписка с id=" + id + " не найдена"));
    }

    /** @param secret секрет подписи — показывается один раз */
    public record Created(WebhookRepository.Subscription subscription, String secret) {
    }
}
