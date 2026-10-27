package com.dima.fooddelivery.notification.telegram;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Опрашивает бота: забирает новые сообщения и отдаёт их {@link TelegramBotHandler}.
 *
 * <p><b>Long polling по умолчанию, вебхук — по настройке.</b> Вебхуку нужен адрес, доступный из
 * интернета по https, а у локального стека его нет; опросу достаточно исходящего соединения.
 * Режим вебхука ({@code app.notification.telegram.mode=webhook}) — {@link TelegramWebhookController}. Запрос висит у Telegram до
 * {@code pollTimeout}, пока нет сообщений, поэтому пустой бот почти не нагружает ни его, ни нас.
 *
 * <p><b>Свой поток, не планировщик Spring.</b> Опрос блокируется на десятки секунд, а планировщик
 * один на всё приложение: на нём живут страховочный опрос уведомлений и публикация, и они встали
 * бы на время каждого запроса.
 *
 * <p><b>Позиция хранится в базе.</b> {@code offset} подтверждает прочитанное; без записи после
 * перезапуска Telegram отдал бы необработанное ещё раз, и уже использованные коды привязки
 * получили бы ответ «ссылка недействительна» на то, что давно сработало. Позиция сохраняется
 * после обработки каждого обновления: при падении посреди пачки повторится не больше одного.
 *
 * <p><b>Опрос ведёт один экземпляр.</b> Второй получил бы от Telegram 409 и отнимал бы обновления у
 * первого. 409 здесь не ошибка, а сигнал «меня уже кто-то опрашивает»: поток ждёт и пробует снова.
 * Отключить опрос на лишних экземплярах — {@code app.notification.telegram.polling-enabled=false}.
 * Отправка уведомлений от опроса не зависит.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class TelegramBotPoller {

    private static final long MAX_BACKOFF_MILLIS = 60_000;
    private static final long CONFLICT_PAUSE_MILLIS = 30_000;

    private final TelegramClient client;
    private final TelegramBotHandler handler;
    private final TelegramLinkRepository repository;
    private final TelegramProperties properties;

    private volatile boolean running;
    private Thread thread;

    public TelegramBotPoller(
            TelegramClient client,
            TelegramBotHandler handler,
            TelegramLinkRepository repository,
            TelegramProperties properties
    ) {
        this.client = client;
        this.handler = handler;
        this.repository = repository;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        if (properties.webhookMode()) {
            log.info("Telegram-бот в режиме вебхука: опрос не запускается (см. TelegramWebhookController)");
            return;
        }
        if (!properties.pollingEnabled()) {
            log.info("Опрос Telegram-бота выключен настройкой; отправка уведомлений работает");
            return;
        }

        running = true;
        thread = new Thread(this::loop, "telegram-poller");
        thread.setDaemon(true);
        thread.start();

        log.info("Опрос Telegram-бота запущен");
    }

    @PreDestroy
    void stop() {
        running = false;

        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        long backoff = 1_000;

        // Вебхук, оставшийся от режима webhook, блокирует getUpdates (409). Снимаем его один раз
        // на старте; не вышло — цикл ниже всё равно повторит попытки с паузой.
        try {
            client.deleteWebhook();
        } catch (TelegramClient.TelegramApiException exception) {
            log.warn("Не удалось снять вебхук Telegram перед опросом: {}", exception.getMessage());
        }

        while (running) {
            try {
                pollOnce();
                backoff = 1_000;
            } catch (TelegramClient.TelegramApiException exception) {
                if (!running) {
                    return;
                }

                if (exception.status() == 409) {
                    log.warn("Бота уже опрашивает другой экземпляр (Telegram ответил 409); жду");
                    pause(CONFLICT_PAUSE_MILLIS);
                } else {
                    log.warn("Опрос Telegram не удался: {}; повтор через {} мс", exception.getMessage(), backoff);
                    pause(backoff);
                    backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
                }
            } catch (RuntimeException exception) {
                // Любой сбой обработки не должен убивать поток: опрос остановился бы молча, и
                // привязки перестали бы работать до перезапуска.
                log.error("Сбой обработки сообщений бота: {}", exception.getMessage(), exception);
                pause(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            }
        }
    }

    /** Один круг опроса: забрать пачку, обработать, сдвинуть позицию. Доступен тестам. */
    void pollOnce() {
        long offset = repository.loadNextOffset();

        List<TelegramClient.Update> updates = client.getUpdates(offset, (int) properties.pollTimeout().toSeconds());

        for (TelegramClient.Update update : updates) {
            try {
                handler.handle(update);
            } catch (TelegramClient.TelegramApiException exception) {
                // Не удалось ответить (человек заблокировал бота сразу после /start) — это не повод
                // застрять на обновлении навсегда: привязка уже сделана, ответ необязателен.
                log.warn("Ответ боту не доставлен: chatId={}, reason={}", update.chatId(), exception.getMessage());
            }

            repository.saveNextOffset(update.updateId() + 1);
        }
    }

    private void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
