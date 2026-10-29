package com.dima.fooddelivery.notification.messenger;

import com.dima.fooddelivery.notification.support.TestcontainersConfiguration;
import com.dima.fooddelivery.notification.telegram.TelegramLinkRepository;
import com.dima.fooddelivery.notification.telegram.TelegramLinkService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уведомления мессенджера через настоящий RabbitMQ в поддельный Bot API: локальный HTTP-сервер отвечает так,
 * как отвечает Telegram (ok, 429 с retry_after, 403 «бот заблокирован»). Настоящий Telegram здесь не нужен —
 * проверяется поведение сервиса на каждом из ответов.
 */
@SpringBootTest(properties = {
        "app.notification.telegram.enabled=true",
        "app.notification.telegram.bot-token=000000:test-token",
        "app.notification.telegram.bot-username=food_delivery_test_bot",
        "app.notification.telegram.polling-enabled=false",
        "app.notification.sweep-enabled=false",
        "logging.level.org.testcontainers=WARN"
})
@Import(TestcontainersConfiguration.class)
class MessengerTelegramIT {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000L + 9_000_000_000L);

    /** chat_id → что ответить: OK, RATE_LIMIT_ONCE, BLOCKED. */
    private static final Map<Long, String> BEHAVIOUR = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer TELEGRAM = fakeTelegram();

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TelegramLinkRepository linkRepository;

    @Autowired
    TelegramLinkService links;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void telegramUrl(DynamicPropertyRegistry registry) {
        registry.add("app.notification.telegram.api-base-url", () -> "http://localhost:" + TELEGRAM.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        TELEGRAM.stop(0);
    }

    @Test
    void deliversOnceEvenIfTaskIsRepeated() throws Exception {
        long user = IDS.incrementAndGet();
        long chat = IDS.incrementAndGet();
        linkRepository.link(user, chat);
        BEHAVIOUR.put(chat, "OK");

        String key = "MESSAGES:" + user + ":1:5";
        publish(key, user);
        publish(key, user);

        awaitStatus(key, "SENT");
        Thread.sleep(500);
        List<Map<String, Object>> toChat = RECEIVED.stream().filter(r -> ((Number) r.get("chat_id")).longValue() == chat).toList();
        assertThat(toChat).hasSize(1);
        assertThat((String) toChat.getFirst().get("text")).startsWith("Мессенджер «Вкусно»\nНовое сообщение").contains("http://localhost:8070/#/messenger/1");
    }

    @Test
    void waitsAsLongAsTelegramAsksOnRateLimit() throws Exception {
        long user = IDS.incrementAndGet();
        long chat = IDS.incrementAndGet();
        linkRepository.link(user, chat);
        BEHAVIOUR.put(chat, "RATE_LIMIT_ONCE");

        String key = "MESSAGES:" + user + ":2:7";
        long started = System.nanoTime();
        publish(key, user);

        awaitStatus(key, "SENT");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        // Ожидание по просьбе Telegram не считается неудачной попыткой.
        assertThat(jdbc.queryForObject("SELECT attempts FROM messenger_delivery WHERE delivery_key = ?", Integer.class, key)).isEqualTo(1);
    }

    @Test
    void blockedBotUnlinksAndExplainsWhy() throws Exception {
        long user = IDS.incrementAndGet();
        long chat = IDS.incrementAndGet();
        linkRepository.link(user, chat);
        BEHAVIOUR.put(chat, "BLOCKED");

        String key = "MISSED_CALL:" + user + ":3:abc";
        publish(key, user);

        awaitStatus(key, "FAILED");
        assertThat(links.chatIdOf(user)).isEmpty();
        assertThat(links.unlinkReason(user)).contains("BOT_BLOCKED");

        // Повторная привязка снимает причину.
        String code = links.issueLinkCode(user).deepLink().replaceAll(".*start=", "");
        links.completeLink(code, chat);
        assertThat(links.unlinkReason(user)).isEmpty();
    }

    @Test
    void notLinkedUserIsSkippedWithoutCallingTelegram() throws Exception {
        long user = IDS.incrementAndGet();
        String key = "MESSAGES:" + user + ":4:1";
        int before = RECEIVED.size();
        publish(key, user);
        awaitStatus(key, "SKIPPED");
        assertThat(RECEIVED.size()).isEqualTo(before);
    }

    private void publish(String key, long user) {
        String json = objectMapper.writeValueAsString(new MessengerNotification(key, user, "MESSAGES", "Мессенджер «Вкусно»",
                "Новое сообщение", "http://localhost:8070/#/messenger/1", OffsetDateTime.now()));
        rabbit.convertAndSend(MessengerTopology.EXCHANGE, MessengerTopology.ROUTING_KEY, json);
    }

    private void awaitStatus(String key, String status) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        String current = null;
        while (System.nanoTime() < deadline) {
            current = jdbc.queryForList("SELECT status FROM messenger_delivery WHERE delivery_key = ?", String.class, key)
                    .stream().findFirst().orElse(null);
            if (status.equals(current)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Статус " + key + ": ожидался " + status + ", а был " + current);
    }

    private static HttpServer fakeTelegram() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            ObjectMapper mapper = new ObjectMapper();
            Map<Long, Boolean> limited = new ConcurrentHashMap<>();
            server.createContext("/", exchange -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = mapper.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                long chat = ((Number) body.getOrDefault("chat_id", 0)).longValue();
                String behaviour = BEHAVIOUR.getOrDefault(chat, "OK");
                int status = 200;
                String reply = "{\"ok\":true,\"result\":{}}";
                if ("BLOCKED".equals(behaviour)) {
                    status = 403;
                    reply = "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}";
                } else if ("RATE_LIMIT_ONCE".equals(behaviour) && limited.putIfAbsent(chat, true) == null) {
                    status = 429;
                    reply = "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 1\",\"parameters\":{\"retry_after\":1}}";
                } else {
                    RECEIVED.add(body);
                }
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
