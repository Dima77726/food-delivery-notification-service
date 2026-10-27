package com.dima.fooddelivery.notification.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Настройки исходящих вебхуков.
 *
 * @param secretKey            ключ AES-256 в Base64, которым шифруются секреты подписок в базе.
 *                             Значения по умолчанию нет: без ключа сервис не стартует
 * @param consumerGroup        своя группа потребителя Kafka: вебхуки читают события заказов
 *                             независимо от уведомлений клиентам — сбой одних не держит других
 * @param retryDelays          лестница пауз между попытками. Попыток всего на одну больше, чем
 *                             ступеней. Ступень — отдельная очередь RabbitMQ со своим TTL
 * @param disableAfterFailures подписка выключается сама после стольких проваленных доставок подряд
 * @param allowedPrivateHosts  хосты, которым разрешены внутренние адреса и http (приёмник для
 *                             демонстрации в compose, localhost в тестах). Всем остальным —
 *                             только https и только публичные адреса
 * @param connectTimeout       ожидание соединения с получателем
 * @param requestTimeout       ожидание ответа получателя целиком
 * @param sweepStaleAfter      доставка, чья очередная попытка запоздала на столько, считается
 *                             потерянной (упало сообщение в брокере) и ставится заново
 */
@ConfigurationProperties(prefix = "app.webhooks")
public record WebhookProperties(
        String secretKey,
        @DefaultValue("notification-webhooks") String consumerGroup,
        @DefaultValue({"PT10S", "PT1M", "PT5M", "PT30M", "PT2H"}) List<Duration> retryDelays,
        @DefaultValue("20") int disableAfterFailures,
        @DefaultValue List<String> allowedPrivateHosts,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration requestTimeout,
        @DefaultValue("PT2M") Duration sweepStaleAfter
) {

    public int maxAttempts() {
        return retryDelays.size() + 1;
    }
}
