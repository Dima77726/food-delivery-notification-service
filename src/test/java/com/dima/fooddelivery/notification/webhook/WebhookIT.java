package com.dima.fooddelivery.notification.webhook;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import com.dima.fooddelivery.notification.support.TestJwt;
import com.dima.fooddelivery.notification.support.TestRestaurantAccess;
import com.dima.fooddelivery.notification.support.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Вебхуки целиком: событие в Kafka → доставка в базе → RabbitMQ → подписанный POST получателю,
 * лестница повторов, автовыключение, ротация секрета. Получатель — HTTP-сервер JDK на localhost
 * (localhost разрешён в тестовой конфигурации; для всех остальных — только публичный https).
 *
 * <p>Лестница в тестах короткая — 0,3 и 0,6 секунды, то есть три попытки; подписка выключается
 * после двух проваленных доставок подряд (см. {@link TestcontainersConfiguration}).
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.notification.sweep-enabled=false",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import(TestcontainersConfiguration.class)
class WebhookIT {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final AtomicLong RESTAURANTS = new AtomicLong(System.nanoTime() % 1_000_000L + 5_000_000L);
    private static final Receiver RECEIVER = Receiver.start();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestJwt jwt;

    @Autowired
    private TestRestaurantAccess restaurantAccess;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private WebhookRepository repository;

    @AfterAll
    static void stop() {
        RECEIVER.server.stop(0);
    }

    @Test
    void shouldDeliverSignedOrderEventToSubscriber() throws Exception {
        Owner owner = newOwner();
        Created hook = subscribe(owner, "ok", List.of("order.paid"));
        UUID eventId = UUID.randomUUID();

        publish(eventId, owner.restaurantId(), "PAID");

        Received request = awaitRequest(hook.path());
        assertEquals(eventId.toString(), request.header("webhook-id"));
        assertEquals("order.paid", JsonPath.read(request.body(), "$.type"));
        assertEquals("PAID", JsonPath.read(request.body(), "$.data.status"));

        String expected = "v1," + WebhookSigner.sign(hook.secret(), eventId.toString(),
                Long.parseLong(request.header("webhook-timestamp")), request.body());
        assertEquals(expected, request.header("webhook-signature"), "подпись должна сходиться с секретом подписки");
        awaitTrue(() -> "SUCCEEDED".equals(onlyDelivery(hook.id()).status()), "доставка не отмечена успешной");
    }

    @Test
    void shouldFilterByEventTypeAndRestaurantAndIgnoreDuplicates() throws Exception {
        Owner owner = newOwner();
        Created hook = subscribe(owner, "ok", List.of("order.delivered"));
        Owner other = newOwner();

        publish(UUID.randomUUID(), owner.restaurantId(), "ACCEPTED");      // не тот тип
        publish(UUID.randomUUID(), other.restaurantId(), "DELIVERED");     // не тот ресторан
        UUID eventId = UUID.randomUUID();
        publish(eventId, owner.restaurantId(), "DELIVERED");
        publish(eventId, owner.restaurantId(), "DELIVERED");               // повтор сообщения Kafka

        awaitRequest(hook.path());
        Thread.sleep(1500);

        assertEquals(1, RECEIVER.requests(hook.path()).size());
        assertEquals(1, repository.findDeliveries(hook.id(), 10).size());
    }

    @Test
    void shouldRetryUntilReceiverRecovers() throws Exception {
        Owner owner = newOwner();
        Created hook = subscribe(owner, "flaky", List.of("*"));
        RECEIVER.script(hook.path(), 500, 503, 200);

        publish(UUID.randomUUID(), owner.restaurantId(), "PAID");

        awaitTrue(() -> "SUCCEEDED".equals(onlyDelivery(hook.id()).status()), "доставка не прошла после повторов");
        WebhookRepository.Delivery delivery = onlyDelivery(hook.id());
        assertEquals(3, delivery.attempts());
        assertEquals(3, RECEIVER.requests(hook.path()).size());
        List<String> ids = RECEIVER.requests(hook.path()).stream().map(r -> r.header("webhook-id")).distinct().toList();
        assertEquals(1, ids.size(), "все попытки — с одним webhook-id");
    }

    @Test
    void shouldFailAfterLastAttemptAndDisableSubscriptionAfterRepeatedFailures() throws Exception {
        Owner owner = newOwner();
        Created hook = subscribe(owner, "dead", List.of("*"));
        RECEIVER.script(hook.path(), 500, 500, 500, 500, 500, 500);

        publish(UUID.randomUUID(), owner.restaurantId(), "PAID");
        awaitTrue(() -> repository.findDeliveries(hook.id(), 10).stream().anyMatch(d -> "FAILED".equals(d.status())),
                "доставка должна провалиться окончательно");
        assertTrue(repository.findSubscription(hook.id()).orElseThrow().active(), "после одной неудачи подписка ещё жива");

        publish(UUID.randomUUID(), owner.restaurantId(), "ACCEPTED");
        awaitTrue(() -> !repository.findSubscription(hook.id()).orElseThrow().active(), "подписка не выключилась");
        assertEquals(500, repository.findDeliveries(hook.id(), 10).getFirst().lastStatusCode());
    }

    @Test
    void shouldDisableSubscriptionImmediatelyOnGone() throws Exception {
        Owner owner = newOwner();
        Created hook = subscribe(owner, "gone", List.of("*"));
        RECEIVER.script(hook.path(), 410);

        publish(UUID.randomUUID(), owner.restaurantId(), "PAID");

        awaitTrue(() -> !repository.findSubscription(hook.id()).orElseThrow().active(), "подписка не выключилась по 410");
        assertEquals(1, RECEIVER.requests(hook.path()).size());
    }

    @Test
    void shouldSignWithBothSecretsDuringRotation() throws Exception {
        Owner owner = newOwner();
        Created hook = subscribe(owner, "rotate", List.of("*"));

        String body = mockMvc.perform(post("/api/v1/restaurants/{r}/webhooks/{id}/rotate-secret", owner.restaurantId(), hook.id())
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newSecret = JsonPath.read(body, "$.secret");

        mockMvc.perform(post("/api/v1/restaurants/{r}/webhooks/{id}/test", owner.restaurantId(), hook.id())
                        .header("Authorization", owner.bearer()))
                .andExpect(status().isAccepted());

        Received request = awaitRequest(hook.path());
        assertEquals("webhook.test", JsonPath.read(request.body(), "$.type"));
        long ts = Long.parseLong(request.header("webhook-timestamp"));
        String id = request.header("webhook-id");
        assertEquals("v1," + WebhookSigner.sign(newSecret, id, ts, request.body())
                        + " v1," + WebhookSigner.sign(hook.secret(), id, ts, request.body()),
                request.header("webhook-signature"));
    }

    @Test
    void shouldRejectInternalAndPlainHttpAddresses() throws Exception {
        Owner owner = newOwner();
        for (String url : List.of("http://10.0.0.5/hook", "https://169.254.169.254/latest/meta-data", "http://example.com/hook", "not a url")) {
            mockMvc.perform(post("/api/v1/restaurants/{r}/webhooks", owner.restaurantId())
                            .header("Authorization", owner.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + url + "\",\"eventTypes\":[\"*\"]}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void shouldHideWebhooksOfForeignRestaurant() throws Exception {
        Owner owner = newOwner();
        Owner stranger = newOwner();

        mockMvc.perform(get("/api/v1/restaurants/{r}/webhooks", owner.restaurantId()).header("Authorization", stranger.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectUnknownEventType() throws Exception {
        Owner owner = newOwner();
        mockMvc.perform(post("/api/v1/restaurants/{r}/webhooks", owner.restaurantId())
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + RECEIVER.url("/x") + "\",\"eventTypes\":[\"order.teleported\"]}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- помощники ----------

    private Created subscribe(Owner owner, String name, List<String> types) throws Exception {
        String path = "/" + name + "-" + UUID.randomUUID();
        String body = mockMvc.perform(post("/api/v1/restaurants/{r}/webhooks", owner.restaurantId())
                        .header("Authorization", owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + RECEIVER.url(path) + "\",\"eventTypes\":["
                                + String.join(",", types.stream().map(t -> "\"" + t + "\"").toList()) + "]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new Created(((Number) JsonPath.read(body, "$.id")).longValue(), JsonPath.read(body, "$.secret"), path);
    }

    private Owner newOwner() {
        long userId = jwt.newUserId();
        long restaurantId = RESTAURANTS.incrementAndGet();
        restaurantAccess.assignManager(userId, restaurantId);
        return new Owner(restaurantId, "Bearer " + jwt.tokenFor(userId, "RESTAURANT_OWNER"));
    }

    private void publish(UUID eventId, long restaurantId, String status) {
        String event = """
                {"eventId":"%s","schemaVersion":1,"eventType":"ORDER_%s","orderId":%d,"customerId":7,"restaurantId":%d,
                 "previousStatus":"CREATED","newStatus":"%s","totalAmount":1250.00,"occurredAt":"%s"}
                """.formatted(eventId, status, restaurantId * 10, restaurantId, status, OffsetDateTime.now());
        kafkaTemplate.send(OrderEventTopics.ORDER_EVENTS, String.valueOf(restaurantId), event).join();
    }

    private WebhookRepository.Delivery onlyDelivery(long subscriptionId) {
        List<WebhookRepository.Delivery> deliveries = repository.findDeliveries(subscriptionId, 10);
        return deliveries.isEmpty() ? new WebhookRepository.Delivery(0, 0, null, null, null, "NONE", 0, null, null, null, null, null, null)
                : deliveries.getFirst();
    }

    private static Received awaitRequest(String path) throws InterruptedException {
        awaitTrue(() -> !RECEIVER.requests(path).isEmpty(), "получатель не получил запрос на " + path);
        return RECEIVER.requests(path).getFirst();
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

    private record Owner(long restaurantId, String bearer) {
    }

    private record Created(long id, String secret, String path) {
    }

    private record Received(Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    /** Получатель: запоминает запросы и отвечает кодами по сценарию пути (по умолчанию 200). */
    private record Receiver(HttpServer server, Map<String, List<Received>> received, Map<String, List<Integer>> scripts) {

        static Receiver start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
                Receiver receiver = new Receiver(server, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
                server.createContext("/", exchange -> {
                    Map<String, String> headers = new ConcurrentHashMap<>();
                    exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String path = exchange.getRequestURI().getPath();
                    receiver.received.computeIfAbsent(path, p -> new CopyOnWriteArrayList<>()).add(new Received(headers, body));
                    List<Integer> script = receiver.scripts.get(path);
                    int code = script == null || script.isEmpty() ? 200 : script.removeFirst();
                    exchange.sendResponseHeaders(code, -1);
                    try (OutputStream ignored = exchange.getResponseBody()) {
                        // тело ответа не нужно
                    }
                });
                server.start();
                return receiver;
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        String url(String path) {
            return "http://localhost:" + server.getAddress().getPort() + path;
        }

        void script(String path, Integer... codes) {
            scripts.put(path, new CopyOnWriteArrayList<>(List.of(codes)));
        }

        List<Received> requests(String path) {
            return received.getOrDefault(path, List.of());
        }
    }
}
