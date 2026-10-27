package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import com.dima.fooddelivery.contracts.order.OrderIntegrationEvent;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.push.PushSubscriptionRepository;
import com.dima.fooddelivery.notification.push.WebPushProperties;
import com.dima.fooddelivery.notification.telegram.TelegramLinkService;
import com.dima.fooddelivery.notification.web.RequestContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Уведомления клиенту, порождённые событиями из Kafka.
 *
 * <p>Этот потребитель жил в монолите, и переезд в отдельный сервис не потребовал от монолита
 * ни строчки: он как публиковал события в топик, так и публикует, а кто их читает — его
 * не касается. Ровно ради этого уведомления в своё время и перевели с внутрипроцессного
 * события на Kafka.
 *
 * <p><b>Группа потребителей сохранена прежней</b> — {@code food-delivery-notifications}.
 * Это и есть бесшовный переезд: офсеты группы хранит брокер, а не процесс, поэтому сервис
 * продолжает читать ровно с того места, где остановился потребитель в монолите. С новой
 * группой и {@code earliest} он перечитал бы топик с начала и разослал бы уведомления
 * по давно доставленным заказам.
 *
 * <p><b>Идемпотентность.</b> Outbox доставляет «хотя бы один раз», значит дубли будут.
 * Защита — уникальный индекс по {@code event_id} в таблице уведомлений: повторная обработка
 * упирается в констрейнт, и это не ошибка, а сигнал «уже сделано».
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventsConsumer {

    /**
     * Не на каждый переход нужно уведомление: клиенту важны оплата, принятие, выезд курьера
     * и доставка. Промежуточная «готовка началась» его только раздражает.
     */
    private static final Map<String, String> CUSTOMER_MESSAGES = Map.of(
            "PAID", "Оплата прошла, заказ передан в ресторан",
            "ACCEPTED", "Ресторан принял ваш заказ",
            "READY_FOR_DELIVERY", "Заказ готов и ждёт курьера",
            "IN_DELIVERY", "Курьер забрал заказ и везёт его вам",
            "DELIVERED", "Заказ доставлен. Приятного аппетита",
            "CANCELED", "Заказ отменён"
    );

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    /** Пуст, пока канал Telegram выключен: тогда события обрабатываются как раньше. */
    private final ObjectProvider<TelegramLinkService> telegramLinks;

    private final PushSubscriptionRepository pushSubscriptions;
    private final WebPushProperties pushProperties;

    @KafkaListener(
            topics = OrderEventTopics.ORDER_EVENTS,
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onOrderEvent(ConsumerRecord<String, String> record) {
        String correlationId = correlationIdOf(record);

        // Восстановление метки — обязательная часть пересечения границы потоков. Здесь
        // начинается новый поток, у которого своего MDC нет, и без этой строки вся работа
        // потребителя выпала бы из цепочки: уведомление невозможно было бы связать
        // с запросом, который его в конечном счёте породил.
        if (correlationId != null) {
            MDC.put(RequestContext.CORRELATION_ID_MDC_KEY, correlationId);
        }

        try {
            handle(record.value(), correlationId);
        } finally {
            MDC.remove(RequestContext.CORRELATION_ID_MDC_KEY);
        }
    }

    private void handle(String payload, String correlationId) {
        OrderIntegrationEvent event = parse(payload);

        String message = CUSTOMER_MESSAGES.get(event.newStatus());

        if (message == null) {
            return;
        }

        enqueue(event, NotificationChannel.EMAIL, message, correlationId);

        // Telegram — только тем, кто его подключил: остальным писать некуда, а «неудачное»
        // уведомление на каждый заказ засоряло бы журнал и очередь.
        TelegramLinkService telegram = telegramLinks.getIfAvailable();

        if (telegram != null && telegram.isLinked(event.customerId())) {
            enqueue(event, NotificationChannel.TELEGRAM, message, correlationId);
        }

        // Web Push — тем, кто разрешил уведомления хотя бы в одном браузере.
        if (pushProperties.configured() && pushSubscriptions.hasAny(event.customerId())) {
            enqueue(event, NotificationChannel.PUSH, message, correlationId);
        }
    }

    /**
     * Уведомление одного канала. Каналы независимы: повтор события не должен ни дублировать
     * доставленное по одному каналу, ни мешать поставить недостающее по другому — идемпотентность
     * держит пара «событие + канал».
     */
    private void enqueue(OrderIntegrationEvent event, NotificationChannel channel, String message, String correlationId) {
        try {
            notificationService.enqueueFromEvent(
                    event.eventId(),
                    event.customerId(),
                    channel,
                    event.eventType(),
                    "Заказ №" + event.orderId(),
                    message,
                    event.orderId(),
                    correlationId
            );
        } catch (DuplicateKeyException duplicate) {
            // Повторная доставка того же события. Штатный исход при гарантии «хотя бы один
            // раз», а не ошибка: бросить исключение здесь означало бы отправить сообщение
            // в DLT и заставить человека разбираться с нормальным поведением системы.
            log.debug("Событие уже обработано, пропускаем: eventId={}, channel={}", event.eventId(), channel);
        }
    }

    /**
     * Метки может не быть: событие могло родиться в фоновой задаче, где никакого
     * HTTP-запроса не было. Это штатный случай, а не потеря данных.
     */
    private String correlationIdOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(RequestContext.CORRELATION_ID_HEADER);

        if (header == null || header.value() == null) {
            return null;
        }

        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /**
     * Неразбираемое сообщение — повод для DLT, а не для бесконечных повторов: сколько
     * ни пытайся, JSON от этого не починится. Исключение выбрасывается наружу, где его
     * ловит настроенный обработчик ошибок.
     */
    private OrderIntegrationEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, OrderIntegrationEvent.class);
        } catch (RuntimeException exception) {
            log.error("Не удалось разобрать событие заказа: {}", exception.getMessage());

            throw exception;
        }
    }
}
