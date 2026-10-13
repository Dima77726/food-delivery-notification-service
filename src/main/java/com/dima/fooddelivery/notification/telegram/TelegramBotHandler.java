package com.dima.fooddelivery.notification.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Что бот отвечает на сообщения. Отдельно от опроса ({@link TelegramBotPoller}), чтобы логика
 * проверялась без потоков и без сети.
 *
 * <p>Команд две: {@code /start <код>} привязывает чат к пользователю, {@code /stop} отвязывает.
 * Всё остальное получает подсказку — бот не собеседник, и молчать на непонятное значило бы
 * оставить человека гадать, работает ли он.
 *
 * <p>Привязки принимаются только из личной переписки: в группе или канале {@code /start} мог бы
 * отправить любой участник, и уведомления о заказе читали бы все.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class TelegramBotHandler {

    static final String HELP = "Это бот уведомлений о заказах. Чтобы подключить его, откройте ссылку "
            + "«Подключить Telegram» в приложении. Команда /stop отключает уведомления.";

    private final TelegramLinkService links;
    private final TelegramClient client;

    public void handle(TelegramClient.Update update) {
        if (!"private".equals(update.chatType())) {
            // Группы и каналы: не отвечаем и ничего не привязываем.
            return;
        }

        String text = update.text() == null ? "" : update.text().trim();

        if (text.equals("/start") || text.startsWith("/start ")) {
            start(update.chatId(), text.substring("/start".length()).trim());
        } else if (text.equals("/stop")) {
            stop(update.chatId());
        } else if (!text.isEmpty()) {
            client.sendMessage(update.chatId(), HELP);
        }
    }

    private void start(long chatId, String code) {
        if (code.isEmpty()) {
            client.sendMessage(chatId, HELP);
            return;
        }

        if (links.completeLink(code, chatId)) {
            client.sendMessage(chatId, "Готово! Теперь уведомления о ваших заказах будут приходить сюда. "
                    + "Отключить: /stop");
        } else {
            client.sendMessage(chatId, "Ссылка недействительна или устарела. Получите новую в приложении.");
        }
    }

    private void stop(long chatId) {
        if (links.unlinkChat(chatId)) {
            client.sendMessage(chatId, "Уведомления отключены. Подключить снова можно по ссылке в приложении.");
        } else {
            client.sendMessage(chatId, "Этот чат и так не подключён.");
        }
    }
}
