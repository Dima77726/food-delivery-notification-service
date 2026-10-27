-- Исходящие вебхуки: подписки ресторанов на события заказов и журнал доставок.

-- Подписка. Секрет подписи хранится зашифрованным (AES-GCM), а не хешем: им подписывается каждый
-- запрос, значит, сервису нужен он сам. После ротации старый секрет действует ещё сутки — получатель
-- успевает перейти, а запросы в это время подписаны обоими.
CREATE TABLE IF NOT EXISTS webhook_subscription
(
    id                     BIGSERIAL     PRIMARY KEY,
    restaurant_id          BIGINT        NOT NULL,
    created_by             BIGINT        NOT NULL,
    url                    VARCHAR(2048) NOT NULL,
    description            VARCHAR(200),
    event_types            TEXT[]        NOT NULL,
    secret_enc             TEXT          NOT NULL,
    previous_secret_enc    TEXT,
    previous_secret_until  TIMESTAMPTZ,
    active                 BOOLEAN       NOT NULL DEFAULT TRUE,
    consecutive_failures   INT           NOT NULL DEFAULT 0,
    disabled_reason        VARCHAR(300),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_webhook_subscription_restaurant
    ON webhook_subscription (restaurant_id) WHERE active;

-- Доставка: одно событие одной подписке. Уникальность пары держит идемпотентность потребителя
-- Kafka: повтор события не создаёт второй доставки. event_id же уходит получателю в заголовке
-- webhook-id и одинаков во всех попытках — по нему получатель отбрасывает повторы у себя.
--
-- locked_until — аренда (lease): обработчик сначала занимает доставку на 30 секунд и только потом
-- ходит по сети. Второй обработчик (повтор из страховочного опроса) занятую не возьмёт, а аренда
-- упавшего истечёт сама.
CREATE TABLE IF NOT EXISTS webhook_delivery
(
    id               BIGSERIAL    PRIMARY KEY,
    subscription_id  BIGINT       NOT NULL,
    event_id         UUID         NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    payload          TEXT         NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    last_status_code INT,
    last_error       VARCHAR(500),
    next_attempt_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_until     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_attempt_at  TIMESTAMPTZ,
    delivered_at     TIMESTAMPTZ,

    CONSTRAINT fk_webhook_delivery_subscription
        FOREIGN KEY (subscription_id) REFERENCES webhook_subscription (id) ON DELETE CASCADE,
    CONSTRAINT uq_webhook_delivery_event UNIQUE (subscription_id, event_id),
    CONSTRAINT chk_webhook_delivery_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_webhook_delivery_subscription
    ON webhook_delivery (subscription_id, id DESC);

-- Страховочный опрос ищет только «зависшие» PENDING — частичный индекс по ним одним.
CREATE INDEX IF NOT EXISTS idx_webhook_delivery_pending
    ON webhook_delivery (next_attempt_at) WHERE status = 'PENDING';

COMMENT ON TABLE webhook_subscription IS 'Подписки ресторанов на события заказов (исходящие вебхуки)';
COMMENT ON TABLE webhook_delivery IS 'Доставки вебхуков: попытки, коды ответа, расписание повторов';
