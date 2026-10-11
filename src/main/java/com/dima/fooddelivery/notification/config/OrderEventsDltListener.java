package com.dima.fooddelivery.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Превращает сообщение в DLT в строку лога уровня ERROR — по ней срабатывает алерт.
 *
 * <p>Ничего не чинит: если три попытки не помогли, четвёртая тем более. И намеренно
 * не бросает исключений — иначе обработчик ошибок отправил бы сообщение в {@code .DLT.DLT}.
 */
@Slf4j
@Component
public class OrderEventsDltListener {

    private static final int PAYLOAD_LOG_LIMIT = 500;

    @KafkaListener(
            topics = KafkaConfig.ORDER_EVENTS_DLT,
            groupId = "${spring.kafka.consumer.group-id}-dlt"
    )
    public void onFailedEvent(ConsumerRecord<String, String> record) {
        log.error(
                "Событие заказа не обработано сервисом уведомлений: key={}, cause={}, payload={}",
                record.key(),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                truncate(record.value())
        );
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);

        if (header == null || header.value() == null) {
            return "неизвестно";
        }

        byte[] value = header.value();

        for (byte b : value) {
            if (b < 0x20 && b != '\n' && b != '\r' && b != '\t') {
                return "0x" + HexFormat.of().formatHex(value);
            }
        }

        return new String(value, StandardCharsets.UTF_8);
    }

    private String truncate(String payload) {
        if (payload == null || payload.length() <= PAYLOAD_LOG_LIMIT) {
            return payload;
        }

        return payload.substring(0, PAYLOAD_LOG_LIMIT) + "…(обрезано)";
    }
}
