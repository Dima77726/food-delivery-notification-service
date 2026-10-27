package com.dima.fooddelivery.notification.webhook;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Топология RabbitMQ для доставки вебхуков: рабочая очередь и лестница очередей ожидания.
 *
 * <pre>
 *  после коммита ─▶ webhooks.dispatch (direct, "deliver") ─▶ webhooks.deliveries ─▶ отправка
 *                         ▲                                                          │ не 2xx
 *                         │ TTL истёк (dead-letter)                                   ▼
 *                         ├──────────── webhooks.retry.PT10S  ◀── попытка 1 провалена
 *                         ├──────────── webhooks.retry.PT1M   ◀── попытка 2
 *                         ├──────────── webhooks.retry.PT5M   ◀── попытка 3
 *                         ├──────────── webhooks.retry.PT30M  ◀── попытка 4
 *                         └──────────── webhooks.retry.PT2H   ◀── попытка 5
 *                                                  попытка 6 провалена ─▶ FAILED в журнале
 * </pre>
 *
 * <p><b>Лестница вместо одной очереди ожидания.</b> TTL сообщения брокер проверяет только у
 * головы очереди: короткий срок застрял бы за длинным. Поэтому у каждой ступени своя очередь со
 * своим TTL на всю очередь — все её сообщения ждут одинаково и выходят по порядку. Так получается
 * экспоненциальная пауза между попытками без планировщика и без сна в потоке.
 *
 * <p><b>Имя ступени — её пауза.</b> Аргументы очереди (TTL) брокер не даёт поменять, а другая
 * пауза — это другая очередь с другим именем: смена настроек не упирается в PRECONDITION_FAILED.
 * Старые ступени остаются пустыми, их можно удалить в интерфейсе RabbitMQ.
 *
 * <p><b>Без jitter.</b> Случайная добавка к паузе разводит повторы во времени, когда получатель
 * поднимается после сбоя и на него разом идут все накопленные доставки. С TTL на очередь её не
 * сделать — пауза общая для всей ступени. Сглаживают другое: ограниченная параллельность
 * обработчика ({@code concurrency}) и то, что доставки разных событий попадали на ступень
 * в разное время. Где jitter нужен по-настоящему — плагин отложенных сообщений или расписание
 * в базе ({@code next_attempt_at}); см. учебник, глава 3.
 *
 * <p><b>Очередь «мёртвых» писем не нужна.</b> Её роль играет сама таблица доставок: провалившиеся
 * лежат там со статусом FAILED, кодом ответа и текстом ошибки, а владелец повторяет их кнопкой.
 */
@Configuration
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookTopology {

    public static final String DISPATCH_EXCHANGE = "webhooks.dispatch";
    public static final String DELIVERIES_QUEUE = "webhooks.deliveries";
    public static final String ROUTING_KEY = "deliver";

    public static String retryQueue(Duration delay) {
        return "webhooks.retry." + delay;
    }

    @Bean
    Declarables webhookDeclarables(WebhookProperties properties) {
        DirectExchange dispatch = new DirectExchange(DISPATCH_EXCHANGE);
        Queue deliveries = QueueBuilder.durable(DELIVERIES_QUEUE).build();

        List<Declarable> declarables = new ArrayList<>(List.of(
                dispatch, deliveries, BindingBuilder.bind(deliveries).to(dispatch).with(ROUTING_KEY)));

        for (Duration delay : properties.retryDelays()) {
            declarables.add(QueueBuilder.durable(retryQueue(delay))
                    .ttl((int) delay.toMillis())
                    .deadLetterExchange(DISPATCH_EXCHANGE)
                    .deadLetterRoutingKey(ROUTING_KEY)
                    .build());
        }

        return new Declarables(declarables);
    }
}
