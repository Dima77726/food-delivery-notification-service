package com.dima.fooddelivery.notification.messenger;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Очереди уведомлений мессенджера.
 *
 * <pre>
 *   messenger-service ──► notifications.messenger (direct) ──telegram──► notifications.messenger.telegram ──► MessengerTelegramListener
 *                                    ▲                                                                           │
 *                                    └──────── dead-letter ◄── notifications.messenger.telegram.delay ◄───────────┘
 *                                                                (per-message TTL: retry_after от Telegram или пауза повтора)
 * </pre>
 *
 * <p>Основную очередь и обменник мессенджер объявляет так же (без аргументов) — кто бы ни поднялся первым,
 * задачи не теряются. Очередь ожидания — только здесь: срок у каждого сообщения свой. Особенность RabbitMQ:
 * сообщение с истёкшим сроком уходит, только дойдя до головы очереди, поэтому длинная пауза задерживает
 * стоящие за ней короткие. Паузы здесь одного порядка (секунды — десятки секунд), и это допустимо.
 */
@Configuration
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class MessengerTopology {

    public static final String EXCHANGE = "notifications.messenger";
    public static final String QUEUE = "notifications.messenger.telegram";
    public static final String DELAY_QUEUE = "notifications.messenger.telegram.delay";
    public static final String ROUTING_KEY = "telegram";

    @Bean
    Declarables messengerQueues() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        Queue queue = QueueBuilder.durable(QUEUE).build();
        Queue delay = QueueBuilder.durable(DELAY_QUEUE)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY)
                .build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY);
        return new Declarables(exchange, queue, delay, binding);
    }
}
