package com.dima.fooddelivery.notification.live;

import com.dima.fooddelivery.notification.api.NotificationResponseMapper;
import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.service.NotificationService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Long polling ленты уведомлений: «ответь, как только появится что-то новее курсора».
 *
 * <p>Есть новое — ответ сразу. Нет — запрос паркуется в {@link DeferredResult}: поток Tomcat
 * свободен, ждёт только соединение. Будит его толчок из RabbitMQ
 * ({@link NotificationLiveTopology}) или таймаут — тогда уходит пустой ответ с тем же курсором,
 * и клиент сразу задаёт вопрос снова.
 *
 * <p>Сравнение с SSE в order-service: здесь каждый ответ — законченный HTTP-обмен с обычным
 * JSON. Его понимает любой клиент и любой прокси, он кэшируется и логируется как все остальные,
 * а курсор делает ответы идемпотентными: повтор того же запроса вернёт то же самое. Плата —
 * лишний запрос на каждое событие и на каждый таймаут.
 *
 * <p>{@link SmartLifecycle}: на остановке все ждущие получают пустой ответ сразу — иначе
 * плавное завершение ждало бы их до таймаута.
 */
@Slf4j
@Component
public class NotificationPollWaiters implements SmartLifecycle {

    static final int MAX_ITEMS = 50;

    private final Map<Long, Set<Waiter>> byRecipient = new ConcurrentHashMap<>();
    private final NotificationService notificationService;
    private volatile boolean running;

    public NotificationPollWaiters(NotificationService notificationService, MeterRegistry meterRegistry) {
        this.notificationService = notificationService;
        Gauge.builder("live.polls.waiting", byRecipient, waiters -> waiters.values().stream().mapToInt(Set::size).sum())
                .description("Долгие запросы уведомлений, ждущие ответа")
                .register(meterRegistry);
    }

    public DeferredResult<NotificationPollResponse> await(Long recipientId, long afterId, Duration timeout) {
        DeferredResult<NotificationPollResponse> result = new DeferredResult<>(timeout.toMillis());

        List<Notification> fresh = notificationService.getNotificationsForUserAfter(recipientId, afterId, MAX_ITEMS);
        if (!fresh.isEmpty()) {
            result.setResult(response(fresh, afterId));
            return result;
        }

        Waiter waiter = new Waiter(result, afterId);
        byRecipient.computeIfAbsent(recipientId, id -> ConcurrentHashMap.newKeySet()).add(waiter);

        result.onTimeout(() -> result.setResult(NotificationPollResponse.empty(afterId)));
        result.onCompletion(() -> forget(recipientId, waiter));

        // Уведомление могло появиться между чтением выше и регистрацией — перепроверка закрывает щель.
        wake(recipientId);

        return result;
    }

    @RabbitListener(queues = "#{notificationLiveQueue.name}", concurrency = "1")
    public void onNudge(Message message) {
        try {
            wake(Long.valueOf(new String(message.getBody(), StandardCharsets.UTF_8)));
        } catch (NumberFormatException exception) {
            log.warn("Непонятный толчок долгим запросам пропущен");
        }
    }

    int waiting(Long recipientId) {
        Set<Waiter> waiters = byRecipient.get(recipientId);
        return waiters == null ? 0 : waiters.size();
    }

    private void wake(Long recipientId) {
        Set<Waiter> waiters = byRecipient.get(recipientId);
        if (waiters == null) {
            return;
        }

        for (Waiter waiter : waiters) {
            try {
                List<Notification> fresh = notificationService.getNotificationsForUserAfter(
                        recipientId, waiter.afterId, MAX_ITEMS);
                if (!fresh.isEmpty()) {
                    waiter.result.setResult(response(fresh, waiter.afterId));
                }
            } catch (RuntimeException exception) {
                log.warn("Не удалось дочитать уведомления: recipientId={}, reason={}", recipientId, exception.getMessage());
            }
        }
    }

    private void forget(Long recipientId, Waiter waiter) {
        Set<Waiter> waiters = byRecipient.get(recipientId);
        if (waiters != null && waiters.remove(waiter) && waiters.isEmpty()) {
            byRecipient.remove(recipientId, waiters);
        }
    }

    private static NotificationPollResponse response(List<Notification> notifications, long afterId) {
        long nextAfter = notifications.stream().mapToLong(Notification::id).max().orElse(afterId);
        return new NotificationPollResponse(NotificationResponseMapper.toResponses(notifications), nextAfter);
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        byRecipient.values().forEach(waiters -> waiters.forEach(waiter ->
                waiter.result.setResult(NotificationPollResponse.empty(waiter.afterId))));
        byRecipient.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private record Waiter(DeferredResult<NotificationPollResponse> result, long afterId) {
    }
}
