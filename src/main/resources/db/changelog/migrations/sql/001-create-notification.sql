-- Таблица уведомлений в собственной базе сервиса.
--
-- Итог миграций 020, 024 и 026 монолита, собранный в одну. Историю миграций переносить
-- незачем: база новая, и ей нужно конечное состояние, а не путь к нему.
--
-- Главное отличие от монолитной версии — нет внешних ключей на app_user и customer_order.
-- Эти таблицы живут в чужой базе, и ссылаться на них констрейнтом невозможно физически.
-- recipient_id и order_id здесь — просто идентификаторы из события. Согласованность
-- обеспечивает не база, а поток событий: уведомление создаётся только по событию
-- заказа, который уже существует.
CREATE TABLE IF NOT EXISTS notification
(
    id             BIGSERIAL PRIMARY KEY,
    -- Идентификатор события, породившего уведомление. Уникален: на этом держится
    -- идемпотентность потребителя при повторной доставке.
    event_id       UUID,
    recipient_id   BIGINT        NOT NULL,
    channel        VARCHAR(50)   NOT NULL,
    type           VARCHAR(100)  NOT NULL,
    subject        VARCHAR(255)  NOT NULL,
    body           VARCHAR(2000) NOT NULL,
    status         VARCHAR(50)   NOT NULL DEFAULT 'PENDING',
    order_id       BIGINT,
    failure_reason VARCHAR(500),
    correlation_id VARCHAR(64),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at        TIMESTAMPTZ,

    CONSTRAINT chk_notification_channel
        CHECK (channel IN ('EMAIL', 'SMS', 'PUSH')),

    CONSTRAINT chk_notification_status
        CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

COMMENT ON TABLE notification IS 'Очередь и журнал уведомлений пользователям';
COMMENT ON COLUMN notification.event_id IS 'Событие, породившее уведомление; защищает от дублей при повторной доставке';
COMMENT ON COLUMN notification.recipient_id IS 'Пользователь из события заказа; внешнего ключа нет — пользователи живут в другом сервисе';
COMMENT ON COLUMN notification.order_id IS 'Заказ из события; внешнего ключа нет — заказы живут в другом сервисе';
COMMENT ON COLUMN notification.correlation_id IS 'Метка исходного запроса, дошедшая через Kafka';

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_event_id
    ON notification (event_id)
    WHERE event_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notification_recipient_id
    ON notification (recipient_id);

-- Частичный индекс под выборку «что осталось отправить»: отправленные уведомления
-- этот запрос не интересуют, а со временем их станет большинство.
CREATE INDEX IF NOT EXISTS idx_notification_pending
    ON notification (created_at)
    WHERE status = 'PENDING';
