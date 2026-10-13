package com.dima.fooddelivery.notification.messaging;

import com.dima.fooddelivery.notification.service.NotificationService;
import com.dima.fooddelivery.notification.service.PermanentDeliveryException;
import com.dima.fooddelivery.notification.web.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Отправляет уведомления по задачам из RabbitMQ.
 *
 * <p>Исходы одной задачи:
 * <ul>
 *   <li><b>отправлено</b> — подтверждение (ack), уведомление в статусе SENT;</li>
 *   <li><b>нечего делать</b> — уведомление уже отправлено или его прямо сейчас отправляет
 *       другой обработчик (повтор задачи от страховочного опроса). Тоже подтверждение;</li>
 *   <li><b>доставить нельзя вообще</b> ({@link PermanentDeliveryException}: получателя нет, он
 *       заблокирован, адреса нет) — уведомление сразу в статусе FAILED с причиной, задача
 *       подтверждается; повторять нечего;</li>
 *   <li><b>сбой, попытки ещё есть</b> — отказ без возврата в очередь: брокер переложит
 *       сообщение в очередь ожидания, а через её TTL вернёт сюда;</li>
 *   <li><b>сбой, попыток больше нет</b> — уведомление в статусе FAILED с причиной, копия
 *       задачи — в {@code notifications.dead} для разбора, сама задача подтверждается.</li>
 * </ul>
 *
 * <p>Счётчик попыток не хранится нигде, кроме самого сообщения: каждый отказ брокер
 * дописывает в заголовок {@code x-death} с указанием очереди.
 */
@Slf4j
@Component
public class NotificationWorker {

    private final NotificationService notificationService;
    private final RabbitTemplate rabbitTemplate;
    private final int maxAttempts;

    public NotificationWorker(
            NotificationService notificationService,
            RabbitTemplate rabbitTemplate,
            @Value("${app.notification.max-attempts:5}") int maxAttempts
    ) {
        this.notificationService = notificationService;
        this.rabbitTemplate = rabbitTemplate;
        this.maxAttempts = maxAttempts;
    }

    @RabbitListener(queues = {
            NotificationTopology.EMAIL_QUEUE,
            NotificationTopology.SMS_QUEUE,
            NotificationTopology.PUSH_QUEUE,
            NotificationTopology.TELEGRAM_QUEUE
    })
    public void onTask(Message message) {
        String correlationId = message.getMessageProperties().getHeader(RequestContext.CORRELATION_ID_HEADER);

        if (correlationId != null) {
            MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, correlationId);
        }

        try {
            handle(message);
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
        }
    }

    private void handle(Message message) {
        Long notificationId = Long.valueOf(new String(message.getBody(), StandardCharsets.UTF_8));
        String queue = message.getMessageProperties().getConsumerQueue();
        int attempt = rejections(message, queue) + 1;

        try {
            if (notificationService.deliver(notificationId)) {
                log.debug("Уведомление отправлено: notificationId={}, attempt={}", notificationId, attempt);
            }
        } catch (PermanentDeliveryException exception) {
            // Повтор ничего не изменит: пять попыток с паузами только держали бы очередь.
            // Транзакция доставки уже откатилась, блокировка строки снята; пометка — отдельной.
            notificationService.markFailed(notificationId, exception.getMessage());

            log.warn("Уведомление не может быть доставлено, повтор не нужен: notificationId={}, reason={}",
                    notificationId, exception.getMessage());
        } catch (RuntimeException exception) {
            if (attempt >= maxAttempts) {
                notificationService.markFailed(notificationId, exception.getMessage());
                rabbitTemplate.send(NotificationTopology.DEAD_EXCHANGE, message.getMessageProperties().getReceivedRoutingKey(), message);

                log.error("Уведомление не отправлено за {} попыток, задача отложена в {}: notificationId={}, reason={}",
                        attempt, NotificationTopology.DEAD_QUEUE, notificationId, exception.getMessage());

                return;
            }

            log.warn("Не удалось отправить уведомление, попытка {} из {}: notificationId={}, reason={}",
                    attempt, maxAttempts, notificationId, exception.getMessage());

            // Отказ без возврата в эту же очередь: сообщение уйдёт по dead-letter exchange
            // в очередь ожидания и вернётся сюда через её TTL.
            throw new AmqpRejectAndDontRequeueException("Повтор позже: " + exception.getMessage(), exception);
        }
    }

    /** Сколько раз сообщение уже отклонялось в этой очереди — по заголовку {@code x-death}. */
    private static int rejections(Message message, String queue) {
        List<Map<String, ?>> deaths = message.getMessageProperties().getXDeathHeader();

        if (deaths == null) {
            return 0;
        }

        return deaths.stream()
                .filter(death -> queue.equals(death.get("queue")) && "rejected".equals(String.valueOf(death.get("reason"))))
                .map(death -> death.get("count"))
                .filter(Number.class::isInstance)
                .mapToInt(count -> ((Number) count).intValue())
                .sum();
    }
}
