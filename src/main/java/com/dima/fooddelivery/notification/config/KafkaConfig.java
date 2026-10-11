package com.dima.fooddelivery.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka на стороне потребителя.
 *
 * <p><b>Основной топик здесь не объявляется.</b> Им владеет продюсер — сервис заказов,
 * он и задаёт число партиций. Объяви его потребитель, и кто стартовал первым, тот и создал
 * бы топик со своими настройками; с одной партицией вместо трёх порядок событий заказа
 * держался бы на случайности.
 *
 * <p><b>DLT — свой.</b> Отказы этого сервиса не смешиваются с отказами соседей: у них
 * разные причины, разный разбор и разные дежурные.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    /** Отказы потребителя уведомлений. Имя — исходный топик, владелец и {@code .DLT}. */
    public static final String ORDER_EVENTS_DLT = "food-delivery.order-events.notification-service.DLT";

    @Bean
    NewTopic orderEventsDltTopic() {
        return TopicBuilder.name(ORDER_EVENTS_DLT)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Три попытки с паузой в секунду, потом сообщение уезжает в DLT. Без предела одно битое
     * сообщение остановило бы потребителя навсегда: Kafka не двигает офсет, пока обработка
     * не завершится успехом.
     *
     * <p>Партиция DLT задана как -1: по умолчанию сохраняется номер исходной партиции,
     * а их у основного топика три против одной здесь.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(ORDER_EVENTS_DLT, -1)
        );

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 3L));

        errorHandler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn(
                        "Ошибка обработки сообщения, попытка {}: topic={}, offset={}, reason={}",
                        deliveryAttempt,
                        record.topic(),
                        record.offset(),
                        exception.getMessage()
                )
        );

        return errorHandler;
    }
}
