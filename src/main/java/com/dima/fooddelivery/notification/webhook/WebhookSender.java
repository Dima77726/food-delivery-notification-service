package com.dima.fooddelivery.notification.webhook;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Одна попытка доставки: подписанный POST на адрес подписки.
 *
 * <p>Успех — любой 2xx. Тело ответа не читается дальше первых байт: получателю незачем что-то
 * возвращать, а большой ответ занимал бы память и время обработчика. Перенаправления не
 * выполняются (см. {@link WebhookUrlGuard}).
 */
@Component
public class WebhookSender {

    static final String USER_AGENT = "FoodDelivery-Webhooks/1.0";

    private final HttpClient http;
    private final WebhookProperties properties;
    private final WebhookUrlGuard urlGuard;
    private final SecretCipher cipher;

    public WebhookSender(WebhookProperties properties, WebhookUrlGuard urlGuard, SecretCipher cipher) {
        this.properties = properties;
        this.urlGuard = urlGuard;
        this.cipher = cipher;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public Result send(WebhookRepository.Subscription subscription, WebhookRepository.Delivery delivery) {
        URI uri;
        try {
            // Повторная проверка перед каждой отправкой: имя могло начать указывать внутрь.
            uri = urlGuard.check(subscription.url());
        } catch (WebhookUrlGuard.InvalidWebhookUrlException exception) {
            return Result.failure(null, "Адрес отклонён: " + exception.getMessage());
        }

        long timestamp = System.currentTimeMillis() / 1000;
        String id = delivery.eventId().toString();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("webhook-id", id)
                .header("webhook-timestamp", String.valueOf(timestamp))
                .header("webhook-signature", WebhookSigner.signatureHeader(secrets(subscription), id, timestamp, delivery.payload()))
                .POST(HttpRequest.BodyPublishers.ofString(delivery.payload()))
                .build();

        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return code >= 200 && code < 300 ? Result.success(code) : Result.failure(code, "Ответ " + code);
        } catch (IOException exception) {
            return Result.failure(null, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Result.failure(null, "Прервано");
        }
    }

    /** Действующий секрет и, пока не истекла ротация, предыдущий. */
    private List<String> secrets(WebhookRepository.Subscription subscription) {
        List<String> secrets = new ArrayList<>(2);
        secrets.add(cipher.decrypt(subscription.secretEnc()));
        if (subscription.previousSecretEnc() != null && subscription.previousSecretUntil() != null
                && subscription.previousSecretUntil().isAfter(OffsetDateTime.now())) {
            secrets.add(cipher.decrypt(subscription.previousSecretEnc()));
        }
        return secrets;
    }

    /**
     * @param statusCode код ответа или {@code null}, если ответа не было (таймаут, отказ соединения)
     */
    public record Result(boolean success, Integer statusCode, String error) {

        static Result success(int code) {
            return new Result(true, code, null);
        }

        static Result failure(Integer code, String error) {
            return new Result(false, code, error);
        }
    }
}
