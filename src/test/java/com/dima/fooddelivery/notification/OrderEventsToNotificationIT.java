package com.dima.fooddelivery.notification;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import com.dima.fooddelivery.notification.config.KafkaConfig;
import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.service.NotificationService;
import com.dima.fooddelivery.notification.support.TestcontainersConfiguration;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Сервис целиком: событие заказа в топике → уведомление в собственной базе → HTTP API.
 *
 * <p>Сообщения пишутся в топик строкой JSON ровно той формы, которую публикует outbox
 * монолита, а не сериализацией {@code OrderIntegrationEvent}. Так тест проверяет контракт
 * на проводе: если монолит и сервис по-разному понимают форму события, расхождение
 * всплывёт здесь, а не в логах стенда.
 *
 * <p>Транзакций у теста нет: потребитель работает в своём потоке и коммитит сам, откатить
 * его работу из теста невозможно. Изоляцию даёт уникальный идентификатор пользователя
 * в каждом тесте.
 */
@SpringBootTest(properties = {
        // Рассылка по таймеру меняла бы статусы уведомлений посреди проверок.
        "app.notification.dispatch-enabled=false",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderEventsToNotificationIT {

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    /** Адрес брокера берётся у контейнера: {@code @ServiceConnection} не переписывает свойства. */
    @Autowired
    private ConfluentKafkaContainer kafkaContainer;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    @Test
    void shouldCreateNotificationFromOrderEvent() {
        long customerId = randomId();
        long orderId = randomId();

        publish(event(UUID.randomUUID(), customerId, orderId, "PAID"), null);

        awaitTrue(() -> !notificationService.getNotificationsForUser(customerId).isEmpty(), "уведомление не появилось");

        List<Notification> notifications = notificationService.getNotificationsForUser(customerId);

        assertAll(
                () -> assertEquals(1, notifications.size()),
                () -> assertEquals("ORDER_PAID", notifications.get(0).type()),
                () -> assertTrue(notifications.get(0).body().contains("Оплата прошла")),
                () -> assertEquals(orderId, notifications.get(0).orderId())
        );
    }

    /**
     * Повторная доставка — штатный режим при гарантии «хотя бы один раз». Одно событие,
     * прочитанное дважды, обязано дать одно уведомление.
     */
    @Test
    void shouldIgnoreRedeliveredEvent() {
        long customerId = randomId();
        UUID eventId = UUID.randomUUID();
        String payload = event(eventId, customerId, randomId(), "PAID");

        publish(payload, null);
        awaitTrue(() -> !notificationService.getNotificationsForUser(customerId).isEmpty(), "первое уведомление не появилось");

        publish(payload, null);
        // Маркер-событие того же пользователя публикуется после дубля. Партиция у сообщений
        // может быть разной, поэтому дождаться маркера ещё не значит, что дубль обработан, —
        // но вместе с паузой это надёжнее голого ожидания.
        publish(event(UUID.randomUUID(), customerId, randomId(), "DELIVERED"), null);
        awaitTrue(() -> notificationService.getNotificationsForUser(customerId).size() >= 2, "маркер не появился");
        sleep(Duration.ofSeconds(2));

        long duplicates = notificationService.getNotificationsForUser(customerId).stream()
                .filter(notification -> "ORDER_PAID".equals(notification.type()))
                .count();

        assertEquals(1, duplicates, "повторная доставка не должна порождать второе уведомление");
    }

    /** Метка запроса пережила две асинхронные границы — outbox в монолите и Kafka. */
    @Test
    void shouldStoreCorrelationIdFromMessageHeader() {
        long customerId = randomId();
        String correlationId = "trace-" + UUID.randomUUID();

        publish(event(UUID.randomUUID(), customerId, randomId(), "ACCEPTED"), correlationId);

        awaitTrue(() -> !notificationService.getNotificationsForUser(customerId).isEmpty(), "уведомление не появилось");

        String stored = jdbcTemplate.queryForObject(
                "SELECT correlation_id FROM notification WHERE recipient_id = ?",
                String.class,
                customerId
        );

        assertEquals(correlationId, stored);
    }

    /**
     * Неразбираемое сообщение обязано уехать в собственный DLT сервиса. Без этого одно битое
     * сообщение остановило бы потребителя навсегда.
     */
    @Test
    void shouldRouteUnparsableMessageToOwnDeadLetterTopic() {
        String key = "broken-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> dltConsumer = createConsumer()) {
            dltConsumer.subscribe(List.of(KafkaConfig.ORDER_EVENTS_DLT));
            dltConsumer.poll(Duration.ofSeconds(5));

            kafkaTemplate.send(OrderEventTopics.ORDER_EVENTS, key, "не json вовсе");

            ConsumerRecord<String, String> failed = awaitRecord(dltConsumer, key);

            assertAll(
                    () -> assertEquals("не json вовсе", failed.value(), "тело должно уехать без изменений"),
                    () -> assertNotNull(
                            failed.headers().lastHeader("kafka_dlt-exception-message"),
                            "в DLT должна попадать причина отказа"
                    )
            );
        }
    }

    /**
     * Токен выпущен монолитом, а проверяется здесь — это и есть проверка того, что
     * у сервисов общий контракт токена.
     */
    @Test
    void shouldReturnOnlyOwnNotificationsToTokenOwner() throws Exception {
        long customerId = randomId();
        long stranger = randomId();

        publish(event(UUID.randomUUID(), customerId, randomId(), "PAID"), null);
        publish(event(UUID.randomUUID(), stranger, randomId(), "PAID"), null);
        awaitTrue(() -> !notificationService.getNotificationsForUser(customerId).isEmpty(), "уведомление не появилось");

        mockMvc.perform(get("/api/v1/notifications/me")
                        .header("Authorization", "Bearer " + token(customerId, "food-delivery-platform"))
                        // Подставленный клиентом идентификатор обязан игнорироваться.
                        .header("X-User-Id", String.valueOf(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].type").value("ORDER_PAID"));
    }

    @Test
    void shouldRejectRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/me"))
                .andExpect(status().isUnauthorized());
    }

    /** Подпись верная, издатель чужой: такой токен выпущен не для нас. */
    @Test
    void shouldRejectTokenFromForeignIssuer() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/me")
                        .header("Authorization", "Bearer " + token(randomId(), "someone-else")))
                .andExpect(status().isUnauthorized());
    }

    private void publish(String payload, String correlationId) {
        ProducerRecord<String, String> record = new ProducerRecord<>(OrderEventTopics.ORDER_EVENTS, payload);

        if (correlationId != null) {
            record.headers().add(OrderEventTopics.CORRELATION_ID_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }

        kafkaTemplate.send(record).join();
    }

    /** Форма события — та, что монолит кладёт в outbox. */
    private String event(UUID eventId, long customerId, long orderId, String newStatus) {
        return """
                {
                  "eventId": "%s",
                  "schemaVersion": 1,
                  "eventType": "ORDER_%s",
                  "orderId": %d,
                  "customerId": %d,
                  "restaurantId": 1,
                  "previousStatus": "CREATED",
                  "newStatus": "%s",
                  "totalAmount": 1250.00,
                  "occurredAt": "%s"
                }
                """.formatted(eventId, newStatus, orderId, customerId, newStatus, OffsetDateTime.now());
    }

    private String token(long userId, String issuer) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(
                new ImmutableSecret<>(jwtSecret.getBytes(StandardCharsets.UTF_8))
        );

        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .subject(String.valueOf(userId))
                .claim("roles", List.of("CUSTOMER"))
                .build();

        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private KafkaConsumer<String, String> createConsumer() {
        Properties props = new Properties();

        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-assertions-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        return new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
    }

    /** Ожидание с запасом: перед DLT обработчик делает три попытки с паузой в секунду. */
    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer, String key) {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }

        throw new AssertionError("Не дождались сообщения в DLT за " + DELIVERY_TIMEOUT);
    }

    private void awaitTrue(BooleanSupplier condition, String description) {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);

        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }

            sleep(Duration.ofMillis(200));
        }

        throw new AssertionError("Не дождались за " + DELIVERY_TIMEOUT + ": " + description);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ожидание прервано", interrupted);
        }
    }

    private long randomId() {
        return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
    }
}
