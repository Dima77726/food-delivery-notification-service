package com.dima.fooddelivery.notification.messaging;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.service.NotificationService;
import com.dima.fooddelivery.notification.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Рассылка через настоящий RabbitMQ: задача после коммита, повторы через очередь ожидания,
 * отказ после последней попытки и страховочный опрос.
 *
 * <p>Отправитель здесь управляемый ({@link ScriptedSender}): для каждого получателя тест
 * задаёт, сколько раз отправка упадёт. Пауза перед повтором и число попыток уменьшены, чтобы
 * цепочка повторов укладывалась в секунды.
 */
@SpringBootTest(properties = {
        "app.notification.sweep-enabled=false",
        "app.notification.retry-delay=PT0.3S",
        "app.notification.max-attempts=3",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import({TestcontainersConfiguration.class, NotificationDeliveryIT.SenderConfiguration.class})
class NotificationDeliveryIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final AtomicLong RECIPIENTS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationSweeper sweeper;

    @Autowired
    private ScriptedSender sender;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSendNotificationRightAfterCommit() {
        long recipient = RECIPIENTS.incrementAndGet();

        Long id = notificationService.enqueue(recipient, NotificationChannel.EMAIL, "ORDER_PAID", "Заказ", "Оплачен", 1L);

        awaitTrue(() -> "SENT".equals(status(id)), "уведомление не отправлено");
        assertEquals(1, sender.attempts(recipient));
    }

    /** Два сбоя подряд — и третья попытка проходит: пауза между ними — TTL очереди ожидания. */
    @Test
    void shouldRetryAfterFailuresUntilSent() {
        long recipient = RECIPIENTS.incrementAndGet();
        sender.failTimes(recipient, 2);

        Long id = notificationService.enqueue(recipient, NotificationChannel.SMS, "ORDER_ACCEPTED", "Заказ", "Принят", 1L);

        awaitTrue(() -> "SENT".equals(status(id)), "уведомление так и не отправлено после повторов");
        assertEquals(3, sender.attempts(recipient));
    }

    /** Попытки кончились: FAILED с причиной, а задача отложена для разбора. */
    @Test
    void shouldGiveUpAfterMaxAttemptsAndParkTask() {
        long recipient = RECIPIENTS.incrementAndGet();
        sender.failTimes(recipient, Integer.MAX_VALUE);

        Long id = notificationService.enqueue(recipient, NotificationChannel.PUSH, "ORDER_CANCELED", "Заказ", "Отменён", 1L);

        awaitTrue(() -> "FAILED".equals(status(id)), "уведомление не помечено как неотправленное");

        Message parked = awaitParked(id);

        assertAll(
                () -> assertEquals(3, sender.attempts(recipient), "попыток должно быть ровно max-attempts"),
                () -> assertTrue(failureReason(id).contains("провайдер недоступен")),
                () -> assertNotNull(parked, "задача должна лежать в notifications.dead"),
                // Срочное уведомление уходит с повышенным приоритетом.
                () -> assertEquals(NotificationPriority.URGENT, parked.getMessageProperties().getPriority())
        );
    }

    /** Задача потерялась (её никто не публиковал) — опрос находит строку в базе и ставит заново. */
    @Test
    void shouldRepublishStalePendingNotification() {
        long recipient = RECIPIENTS.incrementAndGet();

        Long id = jdbcTemplate.queryForObject("""
                        INSERT INTO notification (recipient_id, channel, type, subject, body, status, created_at)
                        VALUES (?, 'EMAIL', 'ORDER_DELIVERED', 'Заказ', 'Доставлен', 'PENDING', CURRENT_TIMESTAMP - INTERVAL '1 hour')
                        RETURNING id
                        """,
                Long.class,
                recipient);

        sweeper.sweep();

        awaitTrue(() -> "SENT".equals(status(id)), "опрос не поставил задачу заново");
    }

    /** Повтор задачи для уже отправленного уведомления — второго письма нет. */
    @Test
    void shouldIgnoreDuplicateTaskForSentNotification() {
        long recipient = RECIPIENTS.incrementAndGet();

        Long id = notificationService.enqueue(recipient, NotificationChannel.EMAIL, "ORDER_PAID", "Заказ", "Оплачен", 1L);
        awaitTrue(() -> "SENT".equals(status(id)), "уведомление не отправлено");

        Notification sent = notificationService.getNotificationsForUser(recipient).get(0);
        sweeperPublish(sent);

        sleep(Duration.ofSeconds(1));
        assertEquals(1, sender.attempts(recipient), "дубль задачи не должен давать второе письмо");
    }

    private void sweeperPublish(Notification notification) {
        rabbitTemplate.send(
                NotificationTopology.DISPATCH_EXCHANGE,
                NotificationTopology.routingKey(notification.channel()),
                new Message(String.valueOf(notification.id()).getBytes(StandardCharsets.UTF_8))
        );
    }

    private Message awaitParked(Long id) {
        Instant deadline = Instant.now().plus(TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            Message message = rabbitTemplate.receive(NotificationTopology.DEAD_QUEUE, 500);

            if (message != null && String.valueOf(id).equals(new String(message.getBody(), StandardCharsets.UTF_8))) {
                return message;
            }
        }

        return null;
    }

    private String status(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id);
    }

    private String failureReason(Long id) {
        return jdbcTemplate.queryForObject("SELECT failure_reason FROM notification WHERE id = ?", String.class, id);
    }

    private static void awaitTrue(BooleanSupplier condition, String message) {
        Instant deadline = Instant.now().plus(TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }

            sleep(Duration.ofMillis(100));
        }

        throw new AssertionError(message + " за " + TIMEOUT);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SenderConfiguration {

        @Bean
        @Primary
        ScriptedSender scriptedSender() {
            return new ScriptedSender();
        }
    }

    /** Отправитель, который падает заданное число раз для заданного получателя. */
    static class ScriptedSender implements NotificationService.NotificationSender {

        private final Map<Long, Integer> failuresLeft = new ConcurrentHashMap<>();
        private final Map<Long, AtomicInteger> attempts = new ConcurrentHashMap<>();
        private final Set<Long> sent = ConcurrentHashMap.newKeySet();

        void failTimes(long recipient, int times) {
            failuresLeft.put(recipient, times);
        }

        int attempts(long recipient) {
            AtomicInteger count = attempts.get(recipient);

            return count == null ? 0 : count.get();
        }

        @Override
        public void send(Notification notification) {
            attempts.computeIfAbsent(notification.recipientId(), id -> new AtomicInteger()).incrementAndGet();

            int left = failuresLeft.getOrDefault(notification.recipientId(), 0);
            if (left > 0) {
                failuresLeft.put(notification.recipientId(), left - 1);
                throw new IllegalStateException("провайдер недоступен");
            }

            sent.add(notification.id());
        }
    }
}
