-- Уведомления мессенджера в Telegram: журнал доставки (защита от дублей и повторов) и причина снятия привязки.

-- Задача от мессенджера приходит с ключом идемпотентности. Строка на ключ: повтор задачи (брокер доставил её
-- ещё раз, мессенджер отправил повторно после обрыва) не уходит в Telegram второй раз.
CREATE TABLE IF NOT EXISTS messenger_delivery
(
    delivery_key VARCHAR(200) PRIMARY KEY,
    user_id      BIGINT       NOT NULL,
    kind         VARCHAR(20)  NOT NULL,
    status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    attempts     INT          NOT NULL DEFAULT 0,
    lease_until  TIMESTAMPTZ,
    last_error   VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at      TIMESTAMPTZ,

    CONSTRAINT chk_messenger_delivery_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'SKIPPED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_messenger_delivery_user ON messenger_delivery (user_id, created_at DESC);

-- Почему привязка снята не пользователем: бот заблокирован или чат удалён. Показывается в настройках,
-- чтобы человек понял, почему уведомления перестали приходить, и мог привязать Telegram заново.
CREATE TABLE IF NOT EXISTS telegram_unlink_reason
(
    user_id     BIGINT PRIMARY KEY,
    reason      VARCHAR(20) NOT NULL,
    unlinked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
