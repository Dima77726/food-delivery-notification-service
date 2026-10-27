-- Подписки браузеров на Web Push. Один пользователь — сколько угодно браузеров и устройств; один адрес
-- push-сервиса (endpoint) — ровно одна подписка: браузер, подписавшийся заново, обновляет её ключи.
-- p256dh и auth — открытый ключ и общий секрет браузера: ими шифруется каждое сообщение (RFC 8291).
CREATE TABLE IF NOT EXISTS push_subscription
(
    id              BIGSERIAL     PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    endpoint        VARCHAR(1024) NOT NULL,
    p256dh          VARCHAR(200)  NOT NULL,
    auth            VARCHAR(100)  NOT NULL,
    user_agent      VARCHAR(300),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_success_at TIMESTAMPTZ,

    CONSTRAINT uq_push_subscription_endpoint UNIQUE (endpoint)
);

CREATE INDEX IF NOT EXISTS idx_push_subscription_user ON push_subscription (user_id);

ALTER TABLE notification DROP CONSTRAINT IF EXISTS chk_notification_channel;
ALTER TABLE notification
    ADD CONSTRAINT chk_notification_channel CHECK (channel IN ('EMAIL', 'SMS', 'PUSH', 'TELEGRAM'));

COMMENT ON TABLE push_subscription IS 'Подписки браузеров на Web Push: адрес push-сервиса и ключи шифрования';
