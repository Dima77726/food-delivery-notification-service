package com.dima.fooddelivery.notification.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link TelegramClient} поверх Bot API по HTTPS.
 *
 * <p><b>Токен бота стоит в пути запроса</b> ({@code /bot<токен>/метод}) — так устроен Bot API, и
 * от этого три правила:
 * <ul>
 *   <li>путь собирается шаблоном, а токен — переменной шаблона: так он не попадает в имя метрики;</li>
 *   <li>наблюдаемость для этого клиента выключена: спан HTTP-клиента записывает полный адрес, а адрес
 *       с токеном ушёл бы в Tempo, куда смотрят многие;</li>
 *   <li>текст ошибок очищается от токена ({@link #sanitize}): исключения Spring и JDK цитируют URL,
 *       а исключение рано или поздно попадает в лог.</li>
 * </ul>
 *
 * <p>Ответ Telegram разбирается вручную, а не по статусу: Bot API отдаёт {@code ok=false}, код и
 * описание в теле, и по ним сервис решает, повторять ли отправку.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class HttpTelegramClient implements TelegramClient {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String token;

    public HttpTelegramClient(TelegramProperties properties, ObjectMapper objectMapper) {
        if (properties.botToken() == null || properties.botToken().isBlank()) {
            throw new IllegalStateException(
                    "app.notification.telegram.enabled=true, но токен бота не задан: укажите TELEGRAM_BOT_TOKEN"
            );
        }

        this.token = properties.botToken();
        this.objectMapper = objectMapper;

        // Таймаут чтения обязан быть длиннее long polling, иначе спокойный бот без сообщений
        // постоянно рвал бы собственное соединение.
        Duration read = properties.readTimeout().compareTo(properties.pollTimeout().plusSeconds(5)) > 0
                ? properties.readTimeout()
                : properties.pollTimeout().plusSeconds(5);

        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(read);

        // Свой билдер, а не внедрённый: автонастроенного RestClient.Builder в сервисе нет (в Spring Boot 4
        // он живёт в отдельном стартере), а нам нужен как раз чистый — без наблюдаемости, см. ниже.
        this.client = RestClient.builder()
                .baseUrl(properties.apiBaseUrl())
                .requestFactory(requestFactory)
                // Без спанов и метрик клиента: в них попал бы адрес с токеном.
                .observationRegistry(io.micrometer.observation.ObservationRegistry.NOOP)
                .build();
    }

    @Override
    public void sendMessage(long chatId, String text) {
        call("sendMessage", Map.of("chat_id", chatId, "text", text));
    }

    @Override
    public List<Update> getUpdates(long offset, int timeoutSeconds) {
        JsonNode result = call("getUpdates", Map.of(
                "offset", offset,
                "timeout", timeoutSeconds,
                // Остальные виды обновлений боту не нужны: незачем получать и подтверждать их.
                "allowed_updates", List.of("message")
        ));

        List<Update> updates = new ArrayList<>();

        for (JsonNode item : result) {
            updates.add(toUpdate(item));
        }

        return updates;
    }

    @Override
    public void setWebhook(String url, String secretToken) {
        call("setWebhook", Map.of(
                "url", url,
                "secret_token", secretToken,
                "allowed_updates", List.of("message")
        ));
    }

    @Override
    public void deleteWebhook() {
        call("deleteWebhook", Map.of("drop_pending_updates", false));
    }

    /**
     * Обновление в том виде, в каком его присылают и getUpdates, и вебхук — формат один.
     * Обновление без текста (стикер, фото) читается и пропускается: offset всё равно двигается.
     */
    static Update toUpdate(JsonNode item) {
        JsonNode message = item.path("message");
        return new Update(
                item.path("update_id").asLong(),
                message.path("chat").path("id").asLong(),
                message.path("chat").path("type").asString(""),
                message.path("text").asString("")
        );
    }

    private JsonNode call(String method, Map<String, Object> body) {
        int status = 0;
        String payload;

        try {
            var response = client.post()
                    .uri("/bot{token}/{method}", token, method)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, reply) -> new Raw(
                            reply.getStatusCode().value(),
                            new String(reply.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    ));

            status = response.status();
            payload = response.body();
        } catch (RuntimeException exception) {
            // Нет ответа: сеть, таймаут, обрыв. Сообщение исключения цитирует адрес — токен вырезается.
            throw new TelegramApiException(0, "Telegram недоступен: " + sanitize(exception.getMessage()), null, null);
        }

        JsonNode parsed = parse(payload);

        if (status == 200 && parsed.path("ok").asBoolean(false)) {
            return parsed.path("result");
        }

        Integer retryAfter = parsed.path("parameters").path("retry_after").isNumber()
                ? parsed.path("parameters").path("retry_after").asInt()
                : null;

        throw new TelegramApiException(
                status,
                "Telegram ответил " + status + ": " + sanitize(parsed.path("description").asString("без описания")),
                retryAfter,
                null
        );
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (RuntimeException exception) {
            // Прокси или Cloudflare могут вернуть не JSON; это тоже «ответа нет», а не ошибка разбора.
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Вырезает токен из текста. Одной замены строки мало: исключения цитируют адрес уже закодированным
     * (двоеточие в токене становится {@code %3A}), и точное совпадение его пропустило бы — тест
     * {@code shouldNeverLeakTokenIntoExceptionMessages} поймал это на живом сообщении. Поэтому, кроме
     * самого токена и его закодированного вида, вырезается любой сегмент пути {@code /bot…/}.
     */
    private String sanitize(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replace(token, "***")
                .replace(java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8), "***")
                .replaceAll("/bot[^/\\s\"]+/", "/bot***/");
    }

    private record Raw(int status, String body) {
    }
}
