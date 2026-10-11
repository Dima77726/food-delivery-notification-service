package com.dima.fooddelivery.notification.persistence;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.domain.NotificationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    private static final RowMapper<Notification> NOTIFICATION = (rs, rowNum) -> new Notification(
            rs.getLong("id"),
            rs.getLong("recipient_id"),
            NotificationChannel.fromDbValue(rs.getString("channel")),
            rs.getString("type"),
            rs.getString("subject"),
            rs.getString("body"),
            NotificationStatus.fromDbValue(rs.getString("status")),
            rs.getObject("order_id", Long.class),
            rs.getString("failure_reason"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("sent_at", OffsetDateTime.class)
    );

    private static final String SELECT_NOTIFICATION = """
            SELECT n.id, n.recipient_id, n.channel, n.type, n.subject, n.body,
                   n.status, n.order_id, n.failure_reason, n.created_at, n.sent_at
            FROM notification n
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Optional<Notification> findById(Long notificationId) {
        return jdbc.query(
                SELECT_NOTIFICATION + " WHERE n.id = :notificationId",
                new MapSqlParameterSource("notificationId", notificationId),
                NOTIFICATION
        ).stream().findFirst();
    }

    public List<Notification> findByRecipientId(Long recipientId) {
        return jdbc.query(
                SELECT_NOTIFICATION + " WHERE n.recipient_id = :recipientId ORDER BY n.created_at DESC, n.id DESC",
                new MapSqlParameterSource("recipientId", recipientId),
                NOTIFICATION
        );
    }

    /**
     * Забирает пачку неотправленных уведомлений, блокируя их за собой.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} — способ раздать очередь нескольким экземплярам
     * приложения без внешнего координатора. Первый процесс блокирует выбранные строки,
     * второй их молча пропускает и берёт следующие. Без этого оба прочитали бы одни и те же
     * PENDING-строки и отправили каждое уведомление дважды.
     *
     * <p>Без {@code SKIP LOCKED} второй процесс не продублировал бы отправку, но встал бы
     * в ожидание блокировки — очередь разбиралась бы строго последовательно.
     *
     * <p>Обязательное условие: вызывать внутри транзакции. Блокировка живёт до её конца,
     * то есть до момента, когда статус уже переписан на SENT.
     */
    public List<Notification> lockPending(int limit) {
        return jdbc.query(
                SELECT_NOTIFICATION + """
                         WHERE n.status = :status
                         ORDER BY n.created_at, n.id
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED
                        """,
                new MapSqlParameterSource()
                        .addValue("status", NotificationStatus.PENDING.name())
                        .addValue("limit", limit),
                NOTIFICATION
        );
    }

    public Long insert(
            Long recipientId,
            NotificationChannel channel,
            String type,
            String subject,
            String body,
            Long orderId
    ) {
        return insert(null, recipientId, channel, type, subject, body, orderId, null);
    }

    /**
     * Вставка с идентификатором события.
     *
     * <p>Уникальный индекс по {@code event_id} превращает повторную обработку одного и того же
     * сообщения в нарушение констрейнта — на этом и держится идемпотентность потребителя.
     * Проверять существование заранее бессмысленно: два экземпляра приложения могут
     * обрабатывать дубликат одновременно, и SELECT перед INSERT их не разведёт.
     */
    public Long insert(
            UUID eventId,
            Long recipientId,
            NotificationChannel channel,
            String type,
            String subject,
            String body,
            Long orderId,
            String correlationId
    ) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("eventId", eventId)
                .addValue("recipientId", recipientId)
                .addValue("channel", channel.name())
                .addValue("type", type)
                .addValue("subject", subject)
                .addValue("body", body)
                .addValue("orderId", orderId)
                .addValue("status", NotificationStatus.PENDING.name())
                .addValue("correlationId", correlationId);

        return jdbc.queryForObject(
                """
                        INSERT INTO notification (
                            event_id, recipient_id, channel, type, subject, body, order_id,
                            status, correlation_id
                        )
                        VALUES (
                            CAST(:eventId AS UUID), :recipientId, :channel, :type, :subject,
                            :body, :orderId, :status, :correlationId
                        )
                        RETURNING id
                        """,
                params,
                Long.class
        );
    }

    public int markSent(Long notificationId) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("notificationId", notificationId)
                .addValue("expected", NotificationStatus.PENDING.name())
                .addValue("next", NotificationStatus.SENT.name());

        return jdbc.update(
                """
                        UPDATE notification
                        SET status = :next,
                            sent_at = CURRENT_TIMESTAMP
                        WHERE id = :notificationId
                          AND status = :expected
                        """,
                params
        );
    }

    public int markFailed(Long notificationId, String failureReason) {
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("notificationId", notificationId)
                .addValue("expected", NotificationStatus.PENDING.name())
                .addValue("next", NotificationStatus.FAILED.name())
                .addValue("failureReason", failureReason);

        return jdbc.update(
                """
                        UPDATE notification
                        SET status = :next,
                            failure_reason = :failureReason
                        WHERE id = :notificationId
                          AND status = :expected
                        """,
                params
        );
    }
}
