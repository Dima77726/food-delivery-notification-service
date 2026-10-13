package com.dima.fooddelivery.notification.messaging;

import com.dima.fooddelivery.notification.service.NotificationService;
import com.dima.fooddelivery.notification.service.PermanentDeliveryException;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Реакция работника очереди на разные сбои отправителя — без брокера и без базы.
 *
 * <p>Постоянная ошибка (получателя нет, он заблокирован) не должна проходить пять кругов через
 * очередь ожидания: безнадёжная задача заняла бы очередь и ничего не изменила бы. Временная —
 * наоборот, обязана вернуться на повтор.
 */
class NotificationWorkerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final NotificationWorker worker = new NotificationWorker(service, rabbit, 5);

    @Test
    void shouldFailPermanentErrorImmediatelyWithoutRetry() {
        when(service.deliver(42L)).thenThrow(new PermanentDeliveryException("Получатель не найден: userId=7"));

        // Исключения наружу нет: задача подтверждается, а не уходит в очередь ожидания.
        assertDoesNotThrow(() -> worker.onTask(task(42L)));

        verify(service).markFailed(42L, "Получатель не найден: userId=7");
        verify(rabbit, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void shouldSendTemporaryErrorBackForRetry() {
        when(service.deliver(42L)).thenThrow(new IllegalStateException("SMTP недоступен"));

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> worker.onTask(task(42L)));

        verify(service, never()).markFailed(any(), any());
    }

    private static Message task(Long notificationId) {
        MessageProperties properties = new MessageProperties();
        properties.setConsumerQueue("notifications.email");
        properties.setReceivedRoutingKey("email");

        return new Message(String.valueOf(notificationId).getBytes(StandardCharsets.UTF_8), properties);
    }
}
