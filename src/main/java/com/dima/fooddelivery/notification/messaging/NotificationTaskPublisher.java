package com.dima.fooddelivery.notification.messaging;

import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.domain.NotificationEnqueued;
import com.dima.fooddelivery.notification.web.RequestContext;
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

/**
 * Ставит задачу на отправку в RabbitMQ — после коммита записи уведомления.
 *
 * <p><b>Почему после коммита, а не в транзакции.</b> Обработчик может взять задачу раньше,
 * чем закончится транзакция, и не найти уведомления, которого ещё не видно. После коммита
 * строка уже есть.
 *
 * <p><b>А если публикация не удалась.</b> Уведомление остаётся в базе в статусе PENDING —
 * база здесь источник правды, а сообщение в очереди только толчок «займись». Потерянный толчок
 * повторит {@link NotificationSweeper}. Поэтому outbox здесь не нужен: в отличие от событий
 * заказа, задача ничего не сообщает внешнему миру — она лишь ускоряет работу, которую
 * и так найдёт страховочный опрос.
 *
 * <p>Тело — идентификатор уведомления, и больше ничего: всё остальное обработчик возьмёт
 * из базы, свежим. Публикация ждёт подтверждения брокера (publisher confirms): без него
 * «отправлено» означало бы «ушло в сокет», а не «брокер записал».
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationTaskPublisher {

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnqueued(NotificationEnqueued event) {
        publish(event.notificationId(), event.channel(), event.type(), event.correlationId());
    }

    /**
     * @return {@code true}, если брокер подтвердил приём
     */
    public boolean publish(Long notificationId, NotificationChannel channel, String type, String correlationId) {
        Message message = MessageBuilder.withBody(String.valueOf(notificationId).getBytes(StandardCharsets.UTF_8))
                .setContentType("text/plain")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setPriority(NotificationPriority.of(type))
                .build();

        if (correlationId != null) {
            message.getMessageProperties().setHeader(RequestContext.CORRELATION_ID_HEADER, correlationId);
        }

        try {
            return Boolean.TRUE.equals(rabbitTemplate.invoke(operations -> {
                operations.send(NotificationTopology.DISPATCH_EXCHANGE, NotificationTopology.routingKey(channel), message);

                return operations.waitForConfirms(5_000);
            }));
        } catch (RuntimeException exception) {
            // Не страшно: уведомление в базе, страховочный опрос поставит задачу заново.
            log.warn("Не удалось поставить задачу в RabbitMQ: notificationId={}, reason={}",
                    notificationId, exception.getMessage());

            return false;
        }
    }
}
