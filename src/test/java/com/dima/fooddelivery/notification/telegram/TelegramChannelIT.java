package com.dima.fooddelivery.notification.telegram;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import com.dima.fooddelivery.contracts.order.OrderIntegrationEvent;
import com.dima.fooddelivery.notification.service.OrderEventsConsumer;
import com.dima.fooddelivery.notification.support.TestJwt;
import com.dima.fooddelivery.notification.support.TestcontainersConfiguration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Канал Telegram на настоящей базе: коды привязки, привязки, уведомления по событиям и ручки.
 *
 * <p>Telegram здесь не участвует: клиент создаётся с фальшивым токеном и ни разу не вызывается.
 * Проверяется то, что принадлежит этому сервису, — хранение, идемпотентность и выбор каналов.
 */
@SpringBootTest(properties = {
        "app.notification.telegram.enabled=true",
        "app.notification.telegram.bot-token=000000:test-token",
        "app.notification.telegram.bot-username=food_delivery_test_bot",
        "app.notification.telegram.polling-enabled=false",
        "app.notification.sweep-enabled=false",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TelegramChannelIT {

    private static final AtomicLong USERS = new AtomicLong(System.nanoTime() % 1_000_000_000L + 7_000_000_000L);

    @Autowired
    private TelegramLinkService links;

    @Autowired
    private TelegramLinkRepository repository;

    @Autowired
    private OrderEventsConsumer consumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestJwt jwt;

    @Test
    void shouldLinkChatWithOneTimeCode() {
        long user = USERS.incrementAndGet();
        String code = codeFrom(links.issueLinkCode(user));

        boolean first = links.completeLink(code, 9_001L);
        boolean replay = links.completeLink(code, 9_002L);

        assertAll(
                () -> assertTrue(first),
                () -> assertFalse(replay, "код одноразовый: вторая попытка не подходит"),
                () -> assertEquals(9_001L, links.chatIdOf(user).orElseThrow(), "повтор не должен перепривязать чат")
        );
    }

    @Test
    void shouldRejectUnknownAndExpiredCode() {
        long user = USERS.incrementAndGet();
        String code = codeFrom(links.issueLinkCode(user));
        // Срок кода истёк: подменяем его прямо в базе, ждать пятнадцать минут незачем.
        jdbc.update("UPDATE telegram_link_code SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE user_id = ?", user);

        assertAll(
                () -> assertFalse(links.completeLink(code, 9_010L), "просроченный код"),
                () -> assertFalse(links.completeLink("не-существует", 9_011L), "неизвестный код")
        );
    }

    /** Новая ссылка отменяет прежнюю: актуальный код один. */
    @Test
    void shouldInvalidatePreviousCodeWhenNewOneIsIssued() {
        long user = USERS.incrementAndGet();
        String oldCode = codeFrom(links.issueLinkCode(user));
        String newCode = codeFrom(links.issueLinkCode(user));

        assertAll(
                () -> assertFalse(links.completeLink(oldCode, 9_020L)),
                () -> assertTrue(links.completeLink(newCode, 9_020L))
        );
    }

    /** Один чат — один пользователь: иначе чужие уведомления уходили бы в чужую переписку. */
    @Test
    void shouldMoveChatToTheNewOwner() {
        long first = USERS.incrementAndGet();
        long second = USERS.incrementAndGet();
        links.completeLink(codeFrom(links.issueLinkCode(first)), 9_030L);

        links.completeLink(codeFrom(links.issueLinkCode(second)), 9_030L);

        assertAll(
                () -> assertFalse(links.isLinked(first), "прежний владелец чата теряет привязку"),
                () -> assertEquals(9_030L, links.chatIdOf(second).orElseThrow())
        );
    }

    @Test
    void shouldStoreCodeOnlyAsHash() {
        long user = USERS.incrementAndGet();
        String code = codeFrom(links.issueLinkCode(user));

        String stored = jdbc.queryForObject("SELECT code_hash FROM telegram_link_code WHERE user_id = ?", String.class, user);

        assertAll(
                () -> assertEquals(TelegramLinkService.hash(code), stored),
                () -> assertFalse(stored.contains(code), "в базе не должно быть самого кода")
        );
    }

    @Test
    void shouldPersistPollOffset() {
        repository.saveNextOffset(41L);
        repository.saveNextOffset(42L);

        assertEquals(42L, repository.loadNextOffset());
    }

    /** У подключившего Telegram событие порождает два уведомления — по почте и в Telegram. */
    @Test
    void shouldEnqueueTelegramNotificationOnlyForLinkedUsers() {
        long linked = USERS.incrementAndGet();
        long plain = USERS.incrementAndGet();
        links.completeLink(codeFrom(links.issueLinkCode(linked)), 9_040L);

        consumer.onOrderEvent(record(event(linked, "PAID")));
        consumer.onOrderEvent(record(event(plain, "PAID")));

        assertAll(
                () -> assertEquals(List.of("EMAIL", "TELEGRAM"), channelsOf(linked)),
                () -> assertEquals(List.of("EMAIL"), channelsOf(plain), "без привязки в Telegram не пишем и «неудачных» строк не плодим")
        );
    }

    /** Повторная доставка события: ни дубля по почте, ни дубля в Telegram. */
    @Test
    void shouldBeIdempotentPerEventAndChannel() {
        long user = USERS.incrementAndGet();
        links.completeLink(codeFrom(links.issueLinkCode(user)), 9_050L);
        OrderIntegrationEvent event = event(user, "ACCEPTED");

        consumer.onOrderEvent(record(event));
        consumer.onOrderEvent(record(event));

        assertEquals(List.of("EMAIL", "TELEGRAM"), channelsOf(user));
    }

    /**
     * Первая доставка шла, пока Telegram не был подключён; пользователь подключил его, и событие
     * пришло снова. Telegram-уведомление должно появиться, а почтовое — не продублироваться.
     */
    @Test
    void shouldAddMissingChannelOnRedeliveryWithoutDuplicatingTheOther() {
        long user = USERS.incrementAndGet();
        OrderIntegrationEvent event = event(user, "DELIVERED");
        consumer.onOrderEvent(record(event));
        assertEquals(List.of("EMAIL"), channelsOf(user));

        links.completeLink(codeFrom(links.issueLinkCode(user)), 9_060L);
        consumer.onOrderEvent(record(event));

        assertEquals(List.of("EMAIL", "TELEGRAM"), channelsOf(user));
    }

    @Test
    void shouldManageOwnLinkThroughApi() throws Exception {
        long user = USERS.incrementAndGet();
        String token = "Bearer " + jwt.tokenFor(user, "CUSTOMER");

        mockMvc.perform(get("/api/v1/notifications/telegram").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linked").value(false));

        mockMvc.perform(post("/api/v1/notifications/telegram/link").header("Authorization", token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deepLink").value(org.hamcrest.Matchers.startsWith("https://t.me/food_delivery_test_bot?start=")))
                .andExpect(jsonPath("$.expiresAt").exists());

        links.completeLink(codeOfLatestLink(user), 9_070L);

        mockMvc.perform(get("/api/v1/notifications/telegram").header("Authorization", token))
                .andExpect(jsonPath("$.linked").value(true));

        mockMvc.perform(delete("/api/v1/notifications/telegram").header("Authorization", token))
                .andExpect(status().isNoContent());

        assertFalse(links.isLinked(user));
    }

    @Test
    void shouldRequireAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/telegram/link")).andExpect(status().isUnauthorized());
    }

    private List<String> channelsOf(long user) {
        return jdbc.queryForList(
                "SELECT channel FROM notification WHERE recipient_id = ? ORDER BY channel", String.class, user
        );
    }

    private OrderIntegrationEvent event(long customerId, String newStatus) {
        return new OrderIntegrationEvent(
                UUID.randomUUID(), OrderIntegrationEvent.CURRENT_SCHEMA_VERSION, "ORDER_" + newStatus,
                USERS.incrementAndGet(), customerId, 15L, "CREATED", newStatus, new BigDecimal("450.10"),
                OffsetDateTime.now()
        );
    }

    private ConsumerRecord<String, String> record(OrderIntegrationEvent event) {
        return new ConsumerRecord<>(
                OrderEventTopics.ORDER_EVENTS, 0, 0L, String.valueOf(event.orderId()), objectMapper.writeValueAsString(event)
        );
    }

    /** Код из ссылки вида {@code https://t.me/бот?start=<код>}. */
    private static String codeFrom(TelegramLinkService.LinkOffer offer) {
        return offer.deepLink().substring(offer.deepLink().indexOf("?start=") + "?start=".length());
    }

    /**
     * Ручка отдаёт ссылку наружу, а код в базе хранится хэшем, поэтому прочитать его из базы нельзя —
     * так и задумано. Для проверки полного круга выдаём код через сервис: он заменяет выданный ручкой.
     */
    private String codeOfLatestLink(long user) {
        return codeFrom(links.issueLinkCode(user));
    }
}
