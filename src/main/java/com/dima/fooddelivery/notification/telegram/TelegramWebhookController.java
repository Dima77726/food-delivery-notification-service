package com.dima.fooddelivery.notification.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Бот в режиме вебхука: Telegram сам присылает каждое обновление POST-запросом.
 *
 * <pre>
 *  polling:  сервис ──getUpdates (висит до 25 с)──▶ Telegram      исходящее соединение, внешний адрес не нужен
 *  webhook:  Telegram ──POST /api/v1/webhooks/telegram──▶ шлюз ──▶ сервис      нужен публичный https
 * </pre>
 *
 * <ul>
 *     <li><b>Подлинность.</b> Telegram не подписывает тело, но возвращает секрет, переданный
 *     при регистрации, в заголовке {@code X-Telegram-Bot-Api-Secret-Token}. Без него любой знающий
 *     адрес мог бы прислать «/start чужой-код» от имени Telegram. Сравнение — за постоянное время;</li>
 *     <li><b>повторы.</b> Telegram повторяет обновление, пока не получит 2xx. Позиция ({@code offset})
 *     та же, что у опроса: обновление с номером меньше сохранённого уже обработано и пропускается;</li>
 *     <li><b>всегда 200 после проверки.</b> Не удалось ответить пользователю (он заблокировал бота) —
 *     повтор этого не исправит, а Telegram копил бы и слал обновление снова и снова.</li>
 * </ul>
 *
 * <p>Регистрация вебхука — на старте ({@code setWebhook}). Не вышла (нет сети, неверный адрес) —
 * сервис всё равно стартует: уведомления клиентам от входящих сообщений бота не зависят.
 */
@Slf4j
@RestController
@ConditionalOnExpression("${app.notification.telegram.enabled:false} and '${app.notification.telegram.mode:polling}' == 'webhook'")
public class TelegramWebhookController {

    public static final String PATH = "/api/v1/webhooks/telegram";
    static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

    private final TelegramClient client;
    private final TelegramBotHandler handler;
    private final TelegramLinkRepository repository;
    private final TelegramProperties properties;
    private final ObjectMapper objectMapper;

    public TelegramWebhookController(
            TelegramClient client,
            TelegramBotHandler handler,
            TelegramLinkRepository repository,
            TelegramProperties properties,
            ObjectMapper objectMapper
    ) {
        if (properties.webhookSecret() == null || properties.webhookSecret().isBlank()) {
            throw new IllegalStateException("app.notification.telegram.mode=webhook, но не задан TELEGRAM_WEBHOOK_SECRET");
        }
        this.client = client;
        this.handler = handler;
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    void register() {
        String base = properties.webhookPublicUrl();
        if (base == null || base.isBlank()) {
            log.warn("Режим вебхука Telegram без TELEGRAM_WEBHOOK_PUBLIC_URL: вебхук не зарегистрирован, "
                    + "обновления придут, только если зарегистрировать его вручную");
            return;
        }
        String url = base.replaceAll("/+$", "") + PATH;
        try {
            client.setWebhook(url, properties.webhookSecret());
            log.info("Вебхук Telegram зарегистрирован: {}", url);
        } catch (TelegramClient.TelegramApiException exception) {
            log.warn("Не удалось зарегистрировать вебхук Telegram на {}: {}", url, exception.getMessage());
        }
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(
            @RequestHeader(name = SECRET_HEADER, required = false) String secret,
            @RequestBody String payload
    ) {
        if (secret == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), properties.webhookSecret().getBytes(StandardCharsets.UTF_8))) {
            log.warn("Вебхук Telegram отклонён: неверный секретный заголовок");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        TelegramClient.Update update = HttpTelegramClient.toUpdate(objectMapper.readTree(payload));

        synchronized (this) {
            // Один экземпляр принимает вебхук последовательно: сравнение с позицией и её запись не
            // должны разойтись у двух одновременных повторов одного обновления.
            if (update.updateId() < repository.loadNextOffset()) {
                return ResponseEntity.ok().build();
            }
            try {
                handler.handle(update);
            } catch (TelegramClient.TelegramApiException exception) {
                log.warn("Ответ боту не доставлен: chatId={}, reason={}", update.chatId(), exception.getMessage());
            }
            repository.saveNextOffset(update.updateId() + 1);
        }

        return ResponseEntity.ok().build();
    }
}
