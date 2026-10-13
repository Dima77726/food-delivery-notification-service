package com.dima.fooddelivery.notification.telegram;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Клиент Bot API против локального HTTP-сервера, изображающего Telegram, — без сети и без токена.
 *
 * <p>Токен стоит в пути запроса, поэтому важнее всего, чтобы он не просачивался в сообщения
 * исключений: те попадают в лог. Форма ответов Telegram здесь взята из документации; живую
 * проверку с настоящим ботом эти тесты не заменяют.
 */
class HttpTelegramClientTest {

    private static final String TOKEN = "123456:SECRET-token_value";

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final Deque<Reply> replies = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Recorded> requests = new ConcurrentLinkedQueue<>();

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldSendMessageAsJsonToTokenizedPath() throws Exception {
        HttpTelegramClient client = client();
        replies.add(new Reply(200, "{\"ok\":true,\"result\":{\"message_id\":1}}"));

        client.sendMessage(42L, "Заказ №82\nРесторан принял ваш заказ");

        Recorded sent = requests.peek();
        JsonNode body = objectMapper.readTree(sent.body());
        assertAll(
                () -> assertEquals("POST", sent.method()),
                () -> assertEquals("/bot" + TOKEN + "/sendMessage", sent.path()),
                () -> assertEquals(42L, body.path("chat_id").asLong()),
                () -> assertEquals("Заказ №82\nРесторан принял ваш заказ", body.path("text").asString(), "кириллица и перевод строки без искажений")
        );
    }

    @Test
    void shouldParseUpdates() throws Exception {
        HttpTelegramClient client = client();
        replies.add(new Reply(200, """
                {"ok":true,"result":[
                  {"update_id":10,"message":{"message_id":1,"chat":{"id":555,"type":"private"},"text":"/start abc"}},
                  {"update_id":11,"message":{"message_id":2,"chat":{"id":-100,"type":"supergroup"},"text":"hi"}},
                  {"update_id":12,"message":{"message_id":3,"chat":{"id":555,"type":"private"}}}
                ]}"""));

        List<TelegramClient.Update> updates = client.getUpdates(10, 0);

        JsonNode request = objectMapper.readTree(requests.peek().body());
        assertAll(
                () -> assertEquals(3, updates.size()),
                () -> assertEquals(new TelegramClient.Update(10, 555, "private", "/start abc"), updates.get(0)),
                () -> assertEquals("supergroup", updates.get(1).chatType()),
                // Сообщение без текста (стикер, фото) не теряется: offset всё равно должен сдвинуться.
                () -> assertEquals("", updates.get(2).text()),
                () -> assertEquals(10L, request.path("offset").asLong()),
                () -> assertEquals("message", request.path("allowed_updates").get(0).asString())
        );
    }

    @Test
    void shouldTurnForbiddenIntoUnavailableChat() throws Exception {
        HttpTelegramClient client = client();
        replies.add(new Reply(403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}"));

        TelegramClient.TelegramApiException error = assertThrows(
                TelegramClient.TelegramApiException.class, () -> client.sendMessage(42L, "текст")
        );

        assertAll(
                () -> assertEquals(403, error.status()),
                () -> assertTrue(error.isChatUnavailable(), "бота заблокировали — писать в этот чат больше нельзя"),
                () -> assertTrue(error.getMessage().contains("bot was blocked"))
        );
    }

    @Test
    void shouldRecognizeMissingChatButNotOtherBadRequests() throws Exception {
        HttpTelegramClient client = client();
        replies.add(new Reply(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}"));
        replies.add(new Reply(400, "{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: message is too long\"}"));

        TelegramClient.TelegramApiException missing = assertThrows(
                TelegramClient.TelegramApiException.class, () -> client.sendMessage(1L, "текст"));
        TelegramClient.TelegramApiException tooLong = assertThrows(
                TelegramClient.TelegramApiException.class, () -> client.sendMessage(1L, "текст"));

        assertAll(
                () -> assertTrue(missing.isChatUnavailable()),
                // Ошибка в нашем сообщении не должна отвязывать живого пользователя.
                () -> assertFalse(tooLong.isChatUnavailable())
        );
    }

    @Test
    void shouldExposeRetryAfterOnRateLimit() throws Exception {
        HttpTelegramClient client = client();
        replies.add(new Reply(429, "{\"ok\":false,\"error_code\":429,\"description\":\"Too Many Requests: retry after 7\",\"parameters\":{\"retry_after\":7}}"));

        TelegramClient.TelegramApiException error = assertThrows(
                TelegramClient.TelegramApiException.class, () -> client.sendMessage(42L, "текст"));

        assertAll(
                () -> assertEquals(429, error.status()),
                () -> assertEquals(7, error.retryAfterSeconds()),
                () -> assertFalse(error.isChatUnavailable(), "слишком часто — временно, повтор поможет")
        );
    }

    @Test
    void shouldTreatNonJsonAnswerAsFailure() throws Exception {
        HttpTelegramClient client = client();
        replies.add(new Reply(502, "<html>Bad Gateway</html>"));

        TelegramClient.TelegramApiException error = assertThrows(
                TelegramClient.TelegramApiException.class, () -> client.sendMessage(42L, "текст"));

        assertEquals(502, error.status());
    }

    /** Исключения Spring и JDK цитируют адрес запроса, а в нём токен: он не должен дойти до лога. */
    @Test
    void shouldNeverLeakTokenIntoExceptionMessages() throws Exception {
        HttpTelegramClient client = client();
        server.stop(0);

        TelegramClient.TelegramApiException unreachable = assertThrows(
                TelegramClient.TelegramApiException.class, () -> client.sendMessage(42L, "текст"));

        assertAll(
                () -> assertEquals(0, unreachable.status(), "ответа не было вовсе"),
                () -> assertFalse(unreachable.getMessage().contains(TOKEN), unreachable.getMessage()),
                () -> assertFalse(unreachable.getMessage().contains("SECRET"), unreachable.getMessage())
        );
    }

    @Test
    void shouldRefuseToStartWithoutToken() {
        TelegramProperties properties = new TelegramProperties(
                true, "", "bot", "http://localhost:1", true,
                Duration.ofSeconds(1), Duration.ofMinutes(15), Duration.ofSeconds(1), Duration.ofSeconds(1)
        );

        assertThrows(IllegalStateException.class,
                () -> new HttpTelegramClient(properties, objectMapper, RestClient.builder()));
    }

    private HttpTelegramClient client() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.add(new Recorded(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(body, StandardCharsets.UTF_8)
            ));

            Reply reply = replies.poll();
            if (reply == null) {
                reply = new Reply(500, "{\"ok\":false,\"description\":\"в тесте не задан ответ\"}");
            }

            byte[] response = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        TelegramProperties properties = new TelegramProperties(
                true, TOKEN, "test_bot", "http://localhost:" + server.getAddress().getPort(), true,
                Duration.ofSeconds(1), Duration.ofMinutes(15), Duration.ofSeconds(2), Duration.ofSeconds(5)
        );

        return new HttpTelegramClient(properties, objectMapper, RestClient.builder());
    }

    private record Reply(int status, String body) {
    }

    private record Recorded(String method, String path, String body) {
    }
}
