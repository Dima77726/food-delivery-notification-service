package com.dima.fooddelivery.notification.live;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Base64UrlNamingStrategy;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Широковещательный канал «у пользователя N новое уведомление» между экземплярами сервиса.
 *
 * <pre>
 *                        notifications.live (fanout)
 *   после коммита ──────────────┬──► notifications.live.&lt;экземпляр 1&gt; ──► его ждущие запросы
 *                               └──► notifications.live.&lt;экземпляр 2&gt; ──► его ждущие запросы
 * </pre>
 *
 * <p>Долгий запрос пользователя висит на одном экземпляре, а уведомление может записать другой —
 * тот, к которому пришло событие из Kafka. Fanout-обмен копирует каждое сообщение во все
 * привязанные очереди, а у каждого экземпляра очередь своя: анонимная, эксклюзивная и
 * самоудаляемая. Экземпляр остановился — его очередь исчезла вместе с соединением, копить
 * сообщения для того, кого нет, брокер не будет.
 *
 * <p>Это противоположность рабочим очередям ({@code NotificationTopology}): там сообщение
 * получает <em>один</em> обработчик из многих (конкурирующие потребители), здесь — <em>все</em>
 * (publish/subscribe). Оба шаблона на одном брокере, различает их только тип обмена и то,
 * общая очередь или у каждого своя.
 *
 * <p>Сообщения не сохраняются на диск и не подтверждаются издателем: потерянный толчок только
 * задержит ответ до таймаута долгого запроса, после чего клиент перезапросит ленту по курсору
 * и получит всё. Платить записью на диск за каждое уведомление ради этого незачем.
 */
@Configuration
public class NotificationLiveTopology {

    public static final String LIVE_EXCHANGE = "notifications.live";

    @Bean
    FanoutExchange notificationLiveExchange() {
        // Не durable: обмен без сохраняемых очередей переживать перезапуск брокера незачем —
        // экземпляры объявят его заново при переподключении.
        return new FanoutExchange(LIVE_EXCHANGE, false, false);
    }

    @Bean
    Queue notificationLiveQueue() {
        return new AnonymousQueue(new Base64UrlNamingStrategy(LIVE_EXCHANGE + "."));
    }

    @Bean
    Binding notificationLiveBinding(Queue notificationLiveQueue, FanoutExchange notificationLiveExchange) {
        return BindingBuilder.bind(notificationLiveQueue).to(notificationLiveExchange);
    }
}
