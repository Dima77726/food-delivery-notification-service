package com.dima.fooddelivery.notification.live;

import com.dima.fooddelivery.notification.domain.NotificationEnqueued;
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
 * После коммита нового уведомления толкает всех экземпляров: «у пользователя N новое».
 *
 * <p>После коммита, а не внутри транзакции: разбуженный запрос сразу читает базу, и до коммита
 * он нового уведомления ещё не увидел бы — проснулся бы впустую и уснул снова.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationLiveBroadcaster {

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEnqueued(NotificationEnqueued event) {
        Message message = MessageBuilder.withBody(String.valueOf(event.recipientId()).getBytes(StandardCharsets.UTF_8))
                .setContentType("text/plain")
                .setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT)
                .build();

        try {
            rabbitTemplate.send(NotificationLiveTopology.LIVE_EXCHANGE, "", message);
        } catch (RuntimeException exception) {
            // Не страшно: ждущий запрос ответит по таймауту, клиент перезапросит ленту по курсору.
            log.debug("Не удалось разослать толчок долгим запросам: recipientId={}, reason={}",
                    event.recipientId(), exception.getMessage());
        }
    }
}
