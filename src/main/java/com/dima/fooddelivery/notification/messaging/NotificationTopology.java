package com.dima.fooddelivery.notification.messaging;

import com.dima.fooddelivery.notification.domain.NotificationChannel;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Топология RabbitMQ для рассылки уведомлений.
 *
 * <pre>
 *                   notifications.dispatch (direct)
 *   публикатор ──────────────┬──── email ──► notifications.email ──► обработчик
 *                            ├──── sms   ──► notifications.sms   ──► обработчик
 *                            └──── push  ──► notifications.push  ──► обработчик
 *                                                │ отказ (reject)
 *                                                ▼
 *                   notifications.retry (direct) ──► notifications.email.retry  (TTL)
 *                                                          │ истёк срок
 *                                                          └──► обратно в notifications.dispatch
 *
 *   попытки кончились ──► notifications.dead (direct) ──► notifications.dead  (разбор человеком)
 * </pre>
 *
 * <p><b>Очередь на канал.</b> У почты, SMS и пушей разные провайдеры, разные лимиты и разные
 * сбои. Лёг SMS-шлюз — копятся SMS, а письма продолжают уходить. Скорость каждого канала
 * регулируется числом обработчиков и {@code prefetch}, то есть по силам его провайдера.
 *
 * <p><b>Повтор через TTL и dead-letter exchange.</b> Отказ отправки — не ошибка обработчика,
 * а «попробуй позже». Отклонённое сообщение брокер сам перекладывает в очередь ожидания,
 * а по истечении её TTL — обратно в рабочую. Ни планировщика, ни сна в потоке обработчика:
 * пауза — свойство очереди. Сколько раз сообщение уже возвращалось, брокер пишет в заголовок
 * {@code x-death}.
 *
 * <p><b>Почему одна задержка, а не нарастающая.</b> TTL здесь задан на очередь, и все её
 * сообщения ждут одинаково. TTL отдельного сообщения брокер проверяет только у головы очереди,
 * и короткий срок застрял бы за длинным. Нарастающая пауза делается лестницей очередей
 * ({@code .retry.10s}, {@code .retry.1m}, …) — дописывается здесь же, когда понадобится.
 *
 * <p><b>Приоритеты.</b> Очереди объявлены с {@code x-max-priority}: срочное уведомление
 * обгоняет обычные, накопившиеся перед ним, — см. {@link NotificationPriority}.
 *
 * <p>Аргументы очереди (TTL, приоритет, DLX) брокер запоминает при объявлении и не даёт
 * поменять: повторное объявление с другими аргументами — ошибка {@code PRECONDITION_FAILED}.
 * Поменяли задержку — удалите очередь {@code *.retry} в интерфейсе RabbitMQ, сервис объявит
 * её заново.
 */
@Configuration
public class NotificationTopology {

    public static final String DISPATCH_EXCHANGE = "notifications.dispatch";
    public static final String RETRY_EXCHANGE = "notifications.retry";
    public static final String DEAD_EXCHANGE = "notifications.dead";

    public static final String DEAD_QUEUE = "notifications.dead";

    public static final String EMAIL_QUEUE = "notifications.email";
    public static final String SMS_QUEUE = "notifications.sms";
    public static final String PUSH_QUEUE = "notifications.push";

    /** Верхняя граница приоритета. Больше десяти брокеру держать дорого и незачем. */
    static final int MAX_PRIORITY = 10;

    public static String routingKey(NotificationChannel channel) {
        return channel.name().toLowerCase(Locale.ROOT);
    }

    public static String queue(NotificationChannel channel) {
        return "notifications." + routingKey(channel);
    }

    @Bean
    Declarables notificationDeclarables(@Value("${app.notification.retry-delay:PT10S}") Duration retryDelay) {
        DirectExchange dispatch = new DirectExchange(DISPATCH_EXCHANGE);
        DirectExchange retry = new DirectExchange(RETRY_EXCHANGE);
        DirectExchange dead = new DirectExchange(DEAD_EXCHANGE);
        Queue deadQueue = QueueBuilder.durable(DEAD_QUEUE).build();

        List<org.springframework.amqp.core.Declarable> declarables = new ArrayList<>(List.of(dispatch, retry, dead, deadQueue));

        for (NotificationChannel channel : NotificationChannel.values()) {
            String key = routingKey(channel);

            Queue work = QueueBuilder.durable(queue(channel))
                    .deadLetterExchange(RETRY_EXCHANGE)
                    .deadLetterRoutingKey(key)
                    .maxPriority(MAX_PRIORITY)
                    .build();

            Queue waiting = QueueBuilder.durable(queue(channel) + ".retry")
                    .ttl((int) retryDelay.toMillis())
                    .deadLetterExchange(DISPATCH_EXCHANGE)
                    .deadLetterRoutingKey(key)
                    .build();

            Binding workBinding = BindingBuilder.bind(work).to(dispatch).with(key);
            Binding waitingBinding = BindingBuilder.bind(waiting).to(retry).with(key);
            Binding deadBinding = BindingBuilder.bind(deadQueue).to(dead).with(key);

            declarables.addAll(List.of(work, waiting, workBinding, waitingBinding, deadBinding));
        }

        return new Declarables(declarables);
    }
}
