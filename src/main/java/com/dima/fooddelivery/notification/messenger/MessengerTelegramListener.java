package com.dima.fooddelivery.notification.messenger;

import com.dima.fooddelivery.notification.telegram.TelegramClient;
import com.dima.fooddelivery.notification.telegram.TelegramLinkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Доставляет уведомления мессенджера в Telegram.
 *
 * <p>Исходы задачи:
 * <ul>
 *   <li><b>дубль</b> — ключ уже отправлен или отброшен: подтверждается без отправки;</li>
 *   <li><b>не тестовый получатель</b> — если задан список {@code messenger-allowed-user-ids}, настоящие сообщения
 *       уходят только этим пользователям (стенд с настоящим ботом), остальные — SKIPPED;</li>
 *   <li><b>Telegram не привязан</b> — SKIPPED;</li>
 *   <li><b>бот заблокирован / чат удалён</b> (403, 400 chat not found) — привязка снимается, причина
 *       сохраняется и видна пользователю в настройках, повторов нет;</li>
 *   <li><b>429 с retry_after</b> — задача ждёт ровно столько, сколько попросил Telegram, и попыткой это не считается;</li>
 *   <li><b>сеть, 5xx</b> — повтор с растущей паузой, после {@code max-attempts} — FAILED.</li>
 * </ul>
 * Любой исход — подтверждение задачи: повтор устраивается публикацией в очередь ожидания, а не отказом,
 * поэтому сбой Telegram никогда не возвращается в мессенджер и не мешает переписке.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class MessengerTelegramListener {

    private final MessengerDeliveryRepository deliveries;
    private final TelegramLinkService links;
    private final TelegramClient telegram;
    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;
    private final Set<Long> allowedUsers;
    private final int maxAttempts;

    public MessengerTelegramListener(MessengerDeliveryRepository deliveries, TelegramLinkService links, TelegramClient telegram,
                                     RabbitTemplate rabbit, ObjectMapper objectMapper,
                                     @Value("${app.notification.telegram.messenger-allowed-user-ids:}") String allowedUsers,
                                     @Value("${app.notification.max-attempts:5}") int maxAttempts) {
        this.deliveries = deliveries;
        this.links = links;
        this.telegram = telegram;
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
        this.allowedUsers = Arrays.stream(allowedUsers.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
        this.maxAttempts = maxAttempts;
        if (!this.allowedUsers.isEmpty()) {
            log.warn("Уведомления мессенджера в Telegram — только тестовым пользователям: {}", this.allowedUsers);
        }
    }

    @RabbitListener(queues = MessengerTopology.QUEUE)
    public void onTask(Message message) {
        MessengerNotification task = objectMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), MessengerNotification.class);

        Optional<Integer> attempt = deliveries.claim(task);
        if (attempt.isEmpty()) {
            log.debug("Уведомление мессенджера уже обработано: key={}", task.key());
            return;
        }

        if (!allowedUsers.isEmpty() && !allowedUsers.contains(task.userId())) {
            deliveries.finish(task.key(), "SKIPPED", "получатель не в списке тестовых");
            return;
        }

        Optional<Long> chatId = links.chatIdOf(task.userId());
        if (chatId.isEmpty()) {
            deliveries.finish(task.key(), "SKIPPED", "Telegram не привязан");
            return;
        }

        try {
            telegram.sendMessage(chatId.get(), task.title() + "\n" + task.body(), "Открыть чат", task.link());
            deliveries.finish(task.key(), "SENT", null);
            log.info("Уведомление мессенджера отправлено в Telegram: userId={}, kind={}", task.userId(), task.kind());
        } catch (TelegramClient.TelegramApiException exception) {
            handleFailure(task, attempt.get(), chatId.get(), exception, message);
        }
    }

    private void handleFailure(MessengerNotification task, int attempt, long chatId, TelegramClient.TelegramApiException exception, Message message) {
        if (exception.isChatUnavailable()) {
            links.unlinkUnreachable(chatId, exception.status() == 403 ? "BOT_BLOCKED" : "CHAT_NOT_FOUND");
            deliveries.finish(task.key(), "FAILED", exception.getMessage());
            log.warn("Бот заблокирован или чат удалён, привязка снята: userId={}", task.userId());
            return;
        }

        if (exception.status() == 429 && exception.retryAfterSeconds() != null) {
            long delayMs = exception.retryAfterSeconds() * 1000L + 250;
            deliveries.release(task.key(), exception.getMessage(), false);
            delay(message, delayMs);
            log.warn("Telegram просит подождать {} с: userId={}", exception.retryAfterSeconds(), task.userId());
            return;
        }

        if (attempt >= maxAttempts) {
            deliveries.finish(task.key(), "FAILED", exception.getMessage());
            log.error("Уведомление мессенджера не доставлено за {} попыток: userId={}, reason={}", attempt, task.userId(), exception.getMessage());
            return;
        }

        long delayMs = Math.min(300_000L, 5_000L * (1L << (attempt - 1)));
        deliveries.release(task.key(), exception.getMessage(), true);
        delay(message, delayMs);
        log.warn("Не удалось отправить в Telegram, попытка {} из {}, повтор через {} мс: userId={}", attempt, maxAttempts, delayMs, task.userId());
    }

    private void delay(Message original, long delayMs) {
        Message copy = MessageBuilder.withBody(original.getBody()).andProperties(original.getMessageProperties())
                .setExpiration(String.valueOf(delayMs)).build();
        rabbit.send("", MessengerTopology.DELAY_QUEUE, copy);
    }
}
