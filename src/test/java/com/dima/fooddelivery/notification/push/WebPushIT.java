package com.dima.fooddelivery.notification.push;

import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.service.NotificationService;
import com.dima.fooddelivery.notification.support.TestJwt;
import com.dima.fooddelivery.notification.support.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web Push целиком, кроме настоящего push-сервиса: его изображает HTTP-сервер JDK на localhost.
 * Проверяются заголовки протокола (aes128gcm, VAPID, TTL), удаление умершей подписки по 410 и
 * отказ сохранить адрес, не похожий на push-сервис.
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.notification.sweep-enabled=false",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import(TestcontainersConfiguration.class)
class WebPushIT {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final PushService PUSH = PushService.start();
    // Ключи «браузера» — из примера RFC 8291: настоящий браузер присылает такие же.
    private static final String UA_PUBLIC = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String UA_AUTH = "BTBZMqHH6r4Tts7J_aSIgg";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestJwt jwt;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private PushSubscriptionRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterAll
    static void stop() {
        PUSH.server.stop(0);
    }

    @Test
    void shouldSendEncryptedVapidSignedPushToEveryBrowserOfUser() throws Exception {
        long user = jwt.newUserId();
        repository.upsert(user, PUSH.url("/ok-" + user), UA_PUBLIC, UA_AUTH, "test");

        Long id = notificationService.enqueue(user, NotificationChannel.PUSH, "ORDER_PAID", "Заказ №1", "Оплачен", 1L);

        awaitTrue(() -> !PUSH.requests("/ok-" + user).isEmpty(), "push-сервис не получил сообщение");
        Map<String, String> headers = PUSH.requests("/ok-" + user).getFirst();
        assertEquals("aes128gcm", headers.get("content-encoding"));
        assertTrue(headers.get("authorization").startsWith("vapid t="));
        assertTrue(headers.get("authorization").endsWith("k=" + TestcontainersConfiguration.VAPID[0]));
        assertEquals("86400", headers.get("ttl"));
        // salt(16) + rs(4) + idlen(1) + ключ(65) + шифротекст с тегом GCM(16)
        assertTrue(Integer.parseInt(headers.get("body-length")) > 16 + 4 + 1 + 65 + 16);
        awaitTrue(() -> "SENT".equals(notificationStatus(id)), "уведомление не отмечено отправленным");
    }

    @Test
    void shouldDropSubscriptionThatPushServiceReportsGone() throws Exception {
        long user = jwt.newUserId();
        repository.upsert(user, PUSH.url("/gone-" + user), UA_PUBLIC, UA_AUTH, "test");

        notificationService.enqueue(user, NotificationChannel.PUSH, "ORDER_PAID", "Заказ №2", "Оплачен", 2L);

        awaitTrue(() -> !repository.hasAny(user), "умершая подписка должна быть удалена");
    }

    @Test
    void shouldAcceptOnlyPushServiceEndpoints() throws Exception {
        long user = jwt.newUserId();
        String bearer = "Bearer " + jwt.tokenFor(user, "CUSTOMER");

        mockMvc.perform(get("/api/v1/notifications/push/public-key").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value(TestcontainersConfiguration.VAPID[0]));

        mockMvc.perform(post("/api/v1/notifications/push/subscriptions").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("https://fcm.googleapis.com/fcm/send/abc" + user)))
                .andExpect(status().isCreated());
        for (String evil : List.of("http://fcm.googleapis.com/x", "https://evil.example/x", "https://fcm.googleapis.com.evil.example/x")) {
            mockMvc.perform(post("/api/v1/notifications/push/subscriptions").header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content(body(evil)))
                    .andExpect(status().isBadRequest());
        }

        assertTrue(repository.hasAny(user));
    }

    private static String body(String endpoint) {
        return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"" + UA_PUBLIC + "\",\"auth\":\"" + UA_AUTH + "\"}}";
    }

    private String notificationStatus(Long id) {
        return jdbc.queryForObject("SELECT status FROM notification WHERE id = ?", String.class, id);
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError(message);
    }

    /** Push-сервис: на путь /gone-* отвечает 410, на остальные — 201, и запоминает заголовки. */
    private record PushService(HttpServer server, Map<String, List<Map<String, String>>> received) {

        static PushService start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                PushService service = new PushService(server, new ConcurrentHashMap<>());
                server.createContext("/", exchange -> {
                    Map<String, String> headers = new ConcurrentHashMap<>();
                    exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
                    headers.put("body-length", String.valueOf(exchange.getRequestBody().readAllBytes().length));
                    String path = exchange.getRequestURI().getPath();
                    service.received.computeIfAbsent(path, p -> new CopyOnWriteArrayList<>()).add(headers);
                    exchange.sendResponseHeaders(path.startsWith("/gone") ? 410 : 201, -1);
                    exchange.close();
                });
                server.start();
                return service;
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        String url(String path) {
            return "http://localhost:" + server.getAddress().getPort() + path;
        }

        List<Map<String, String>> requests(String path) {
            return received.getOrDefault(path, List.of());
        }
    }
}
