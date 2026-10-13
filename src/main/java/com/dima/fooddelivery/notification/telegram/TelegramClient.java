package com.dima.fooddelivery.notification.telegram;

import java.util.List;

/**
 * Что сервису нужно от Telegram Bot API: отправить сообщение и получить новые. Интерфейс, а не
 * прямые HTTP-вызовы, чтобы отправитель и обработчик бота проверялись без сети.
 */
public interface TelegramClient {

    /**
     * @throws TelegramApiException если Telegram отказал или не ответил
     */
    void sendMessage(long chatId, String text);

    /**
     * Новые обновления бота. Запрос висит до {@code timeoutSeconds}, пока их нет (long polling).
     *
     * @param offset с какого {@code update_id} читать; меньшие Telegram считает прочитанными
     */
    List<Update> getUpdates(long offset, int timeoutSeconds);

    /**
     * Входящее сообщение боту. Остальные виды обновлений (правка, нажатие кнопки…) сюда не попадают —
     * бот на них не рассчитан.
     *
     * @param chatType {@code private} для личной переписки; в группах и каналах бот привязок не принимает
     */
    record Update(long updateId, long chatId, String chatType, String text) {
    }

    /**
     * Отказ или недоступность Bot API.
     *
     * <p>Код — HTTP-статус ответа Telegram ({@code 0}, если ответа не было вовсе). По нему отправитель
     * отличает «чат недоступен навсегда» (403 — бота заблокировали, 400 — чата нет) от временного
     * (429 — слишком часто, 5xx и сеть).
     */
    class TelegramApiException extends RuntimeException {

        private final int status;
        private final Integer retryAfterSeconds;

        public TelegramApiException(int status, String message, Integer retryAfterSeconds, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public TelegramApiException(int status, String message) {
            this(status, message, null, null);
        }

        public int status() {
            return status;
        }

        /** Сколько Telegram просит подождать (ответ 429); {@code null}, если не просил. */
        public Integer retryAfterSeconds() {
            return retryAfterSeconds;
        }

        /**
         * Чат недоступен насовсем: бота заблокировали (403) или чата нет (400 «chat not found»).
         * Повтор не поможет, а привязку пора снять.
         *
         * <p>Любой другой 400 — не про чат (текст слишком длинный, разметка не разобралась) и привязку
         * снимать не повод: иначе ошибка в нашем сообщении отвязывала бы живого пользователя.
         */
        public boolean isChatUnavailable() {
            return status == 403 || (status == 400 && getMessage() != null && getMessage().contains("chat not found"));
        }
    }
}
