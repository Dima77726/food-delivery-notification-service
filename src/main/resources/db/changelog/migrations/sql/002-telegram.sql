-- Канал Telegram: привязка аккаунта к пользователю и состояние опроса бота.

-- 1. Новый канал. Список допустимых значений закреплён constraint'ом, поэтому он пересоздаётся.
ALTER TABLE notification DROP CONSTRAINT IF EXISTS chk_notification_channel;
ALTER TABLE notification
    ADD CONSTRAINT chk_notification_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'TELEGRAM'));

-- 2. Одно событие теперь может породить несколько уведомлений — по одному на канал, поэтому
-- идемпотентность держит пара «событие + канал», а не одно событие.
DROP INDEX IF EXISTS uq_notification_event_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_event_channel
    ON notification (event_id, channel)
    WHERE event_id IS NOT NULL;

-- 3. Привязка: какому чату Telegram писать пользователю.
-- Пользователь один — чат один: повторная привязка заменяет прежнюю. И наоборот, один чат не может
-- принадлежать двум пользователям, иначе чужие уведомления уходили бы в чужой чат.
CREATE TABLE IF NOT EXISTS telegram_link
(
    user_id   BIGINT PRIMARY KEY,
    chat_id   BIGINT      NOT NULL,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_telegram_link_chat UNIQUE (chat_id)
);

COMMENT ON TABLE telegram_link IS 'Чат Telegram, в который пользователь получает уведомления';
COMMENT ON COLUMN telegram_link.user_id IS 'Пользователь из identity-service; внешнего ключа нет — он живёт в другой базе';

-- 4. Одноразовые коды привязки. Хранится не код, а его SHA-256: код дают человеку в ссылке, и
-- утечка таблицы не должна позволять привязать чужой аккаунт.
CREATE TABLE IF NOT EXISTS telegram_link_code
(
    user_id    BIGINT PRIMARY KEY,
    code_hash  VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_telegram_link_code_hash UNIQUE (code_hash)
);

COMMENT ON TABLE telegram_link_code IS 'Код, по которому пользователь привязывает Telegram; один актуальный на пользователя';

-- 5. Позиция опроса бота (offset getUpdates). Без неё после перезапуска Telegram отдал бы
-- необработанные обновления ещё раз — и уже использованные коды привязки получили бы ответ
-- «ссылка недействительна» на то, что давно сработало.
CREATE TABLE IF NOT EXISTS telegram_poll_state
(
    id          SMALLINT PRIMARY KEY,
    next_offset BIGINT NOT NULL,

    CONSTRAINT chk_telegram_poll_state_single CHECK (id = 1)
);

COMMENT ON TABLE telegram_poll_state IS 'Единственная строка: с какого update_id продолжать опрос бота';
