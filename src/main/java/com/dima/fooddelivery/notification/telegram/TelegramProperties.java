package com.dima.fooddelivery.notification.telegram;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Настройки канала Telegram ({@code app.notification.telegram.*}).
 *
 * @param enabled        канал включён. Выключенный не создаёт ни одного бина: ни клиента, ни опроса
 *                       бота, ни ручек привязки, и ключа для запуска не требует
 * @param botToken       токен бота от @BotFather. Значения по умолчанию нет — это секрет; по токену
 *                       можно писать от имени бота кому угодно, поэтому он не попадает ни в лог, ни в трассы
 * @param botUsername    имя бота без {@code @}: из него собирается ссылка привязки {@code t.me/<имя>?start=<код>}
 * @param apiBaseUrl     адрес Bot API; свой — только в тестах, где вместо Telegram отвечает заглушка
 * @param pollingEnabled опрашивать ли бота. Опрос должен вести ровно один экземпляр сервиса: второй
 *                       получит от Telegram отказ 409 (см. {@link TelegramBotPoller})
 * @param pollTimeout    сколько Telegram держит запрос открытым, пока нет обновлений (long polling)
 * @param linkTtl        сколько живёт код привязки
 */
@ConfigurationProperties(prefix = "app.notification.telegram")
public record TelegramProperties(
        @DefaultValue("false") boolean enabled,
        String botToken,
        String botUsername,
        @DefaultValue("https://api.telegram.org") String apiBaseUrl,
        @DefaultValue("true") boolean pollingEnabled,
        @DefaultValue("25s") Duration pollTimeout,
        @DefaultValue("15m") Duration linkTtl,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout
) {
}
