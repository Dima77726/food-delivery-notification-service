package com.dima.fooddelivery.notification.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Подписки и доставки вебхуков. SQL руками, как во всём сервисе.
 */
@Repository
@RequiredArgsConstructor
public class WebhookRepository {

    private static final RowMapper<Subscription> SUBSCRIPTION = (rs, rowNum) -> new Subscription(
            rs.getLong("id"),
            rs.getLong("restaurant_id"),
            rs.getLong("created_by"),
            rs.getString("url"),
            rs.getString("description"),
            toList(rs.getArray("event_types")),
            rs.getString("secret_enc"),
            rs.getString("previous_secret_enc"),
            rs.getObject("previous_secret_until", OffsetDateTime.class),
            rs.getBoolean("active"),
            rs.getInt("consecutive_failures"),
            rs.getString("disabled_reason"),
            rs.getObject("created_at", OffsetDateTime.class)
    );

    private static final RowMapper<Delivery> DELIVERY = (rs, rowNum) -> new Delivery(
            rs.getLong("id"),
            rs.getLong("subscription_id"),
            rs.getObject("event_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getString("status"),
            rs.getInt("attempts"),
            rs.getObject("last_status_code", Integer.class),
            rs.getString("last_error"),
            rs.getObject("next_attempt_at", OffsetDateTime.class),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("last_attempt_at", OffsetDateTime.class),
            rs.getObject("delivered_at", OffsetDateTime.class)
    );

    private static final String SELECT_SUBSCRIPTION = """
            SELECT id, restaurant_id, created_by, url, description, event_types, secret_enc, previous_secret_enc,
                   previous_secret_until, active, consecutive_failures, disabled_reason, created_at
            FROM webhook_subscription
            """;

    private static final String SELECT_DELIVERY = """
            SELECT id, subscription_id, event_id, event_type, payload, status, attempts, last_status_code, last_error,
                   next_attempt_at, created_at, last_attempt_at, delivered_at
            FROM webhook_delivery
            """;

    private final JdbcTemplate jdbc;

    // ---------- Подписки ----------

    public long insertSubscription(long restaurantId, long createdBy, String url, String description,
                                   List<String> eventTypes, String secretEnc) {
        return jdbc.queryForObject("""
                INSERT INTO webhook_subscription (restaurant_id, created_by, url, description, event_types, secret_enc)
                VALUES (?, ?, ?, ?, CAST(? AS TEXT[]), ?) RETURNING id
                """, Long.class, restaurantId, createdBy, url, description, arrayLiteral(eventTypes), secretEnc);
    }

    public Optional<Subscription> findSubscription(long id) {
        return jdbc.query(SELECT_SUBSCRIPTION + " WHERE id = ?", SUBSCRIPTION, id).stream().findFirst();
    }

    public List<Subscription> findByRestaurant(long restaurantId) {
        return jdbc.query(SELECT_SUBSCRIPTION + " WHERE restaurant_id = ? ORDER BY id", SUBSCRIPTION, restaurantId);
    }

    /** Действующие подписки ресторана на этот тип события (или на все — «*»). */
    public List<Subscription> findActiveFor(long restaurantId, String eventType) {
        return jdbc.query(SELECT_SUBSCRIPTION + """
                 WHERE restaurant_id = ? AND active AND (? = ANY (event_types) OR '*' = ANY (event_types))
                """, SUBSCRIPTION, restaurantId, eventType);
    }

    public boolean deleteSubscription(long id, long restaurantId) {
        return jdbc.update("DELETE FROM webhook_subscription WHERE id = ? AND restaurant_id = ?", id, restaurantId) == 1;
    }

    /** Новый секрет; старый действует ещё {@code grace} — запросы в это время подписаны обоими. */
    public void rotateSecret(long id, String newSecretEnc, Duration grace) {
        jdbc.update("""
                UPDATE webhook_subscription
                SET previous_secret_enc = secret_enc,
                    previous_secret_until = CURRENT_TIMESTAMP + CAST(? AS INTERVAL),
                    secret_enc = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, grace.toSeconds() + " seconds", newSecretEnc, id);
    }

    public void enable(long id) {
        jdbc.update("""
                UPDATE webhook_subscription SET active = TRUE, consecutive_failures = 0, disabled_reason = NULL,
                       updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, id);
    }

    public void disable(long id, String reason) {
        jdbc.update("""
                UPDATE webhook_subscription SET active = FALSE, disabled_reason = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, reason, id);
    }

    public void resetFailures(long id) {
        jdbc.update("UPDATE webhook_subscription SET consecutive_failures = 0 WHERE id = ? AND consecutive_failures <> 0", id);
    }

    /** @return сколько доставок подряд провалилось теперь */
    public int incrementFailures(long id) {
        return jdbc.queryForObject("""
                UPDATE webhook_subscription SET consecutive_failures = consecutive_failures + 1
                WHERE id = ? RETURNING consecutive_failures
                """, Integer.class, id);
    }

    // ---------- Доставки ----------

    /**
     * @return идентификатор новой доставки или пусто, если эта подписка это событие уже получила
     *         (повтор сообщения Kafka)
     */
    public Optional<Long> insertDelivery(long subscriptionId, UUID eventId, String eventType, String payload) {
        return jdbc.queryForList("""
                INSERT INTO webhook_delivery (subscription_id, event_id, event_type, payload)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (subscription_id, event_id) DO NOTHING
                RETURNING id
                """, Long.class, subscriptionId, eventId, eventType, payload).stream().findFirst();
    }

    public Optional<Delivery> findDelivery(long id) {
        return jdbc.query(SELECT_DELIVERY + " WHERE id = ?", DELIVERY, id).stream().findFirst();
    }

    public List<Delivery> findDeliveries(long subscriptionId, int limit) {
        return jdbc.query(SELECT_DELIVERY + " WHERE subscription_id = ? ORDER BY id DESC LIMIT ?", DELIVERY, subscriptionId, limit);
    }

    /**
     * Аренда: занять доставку на {@code lease}, если она ждёт отправки и не занята. Только
     * занявший ходит по сети — два обработчика одной доставки не отправят её дважды одновременно.
     */
    public boolean tryLease(long id, Duration lease) {
        return jdbc.update("""
                UPDATE webhook_delivery SET locked_until = CURRENT_TIMESTAMP + CAST(? AS INTERVAL)
                WHERE id = ? AND status = 'PENDING' AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                """, lease.toSeconds() + " seconds", id) == 1;
    }

    public void markSucceeded(long id, int statusCode) {
        jdbc.update("""
                UPDATE webhook_delivery
                SET status = 'SUCCEEDED', attempts = attempts + 1, last_status_code = ?, last_error = NULL,
                    last_attempt_at = CURRENT_TIMESTAMP, delivered_at = CURRENT_TIMESTAMP, locked_until = NULL
                WHERE id = ?
                """, statusCode, id);
    }

    /** Неудачная попытка; доставка ждёт следующую через {@code retryIn}. @return попыток всего */
    public int markRetry(long id, Integer statusCode, String error, Duration retryIn) {
        return jdbc.queryForObject("""
                UPDATE webhook_delivery
                SET attempts = attempts + 1, last_status_code = ?, last_error = ?, last_attempt_at = CURRENT_TIMESTAMP,
                    next_attempt_at = CURRENT_TIMESTAMP + CAST(? AS INTERVAL), locked_until = NULL
                WHERE id = ? RETURNING attempts
                """, Integer.class, statusCode, truncate(error), retryIn.toMillis() + " milliseconds", id);
    }

    public void markFailed(long id, Integer statusCode, String error, boolean countAttempt) {
        jdbc.update("""
                UPDATE webhook_delivery
                SET status = 'FAILED', attempts = attempts + ?, last_status_code = COALESCE(?, last_status_code),
                    last_error = ?, last_attempt_at = CURRENT_TIMESTAMP, locked_until = NULL
                WHERE id = ?
                """, countAttempt ? 1 : 0, statusCode, truncate(error), id);
    }

    /** Повторная доставка вручную: та же доставка (тот же webhook-id), лестница повторов заново. */
    public boolean resetForRedelivery(long id, long subscriptionId) {
        return jdbc.update("""
                UPDATE webhook_delivery SET status = 'PENDING', attempts = 0, next_attempt_at = CURRENT_TIMESTAMP,
                       locked_until = NULL, delivered_at = NULL
                WHERE id = ? AND subscription_id = ?
                """, id, subscriptionId) == 1;
    }

    /** Доставки, чья попытка запоздала больше чем на {@code staleAfter}: их сообщение потерялось. */
    public List<Long> findStalePending(Duration staleAfter, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM webhook_delivery
                WHERE status = 'PENDING'
                  AND next_attempt_at < CURRENT_TIMESTAMP - CAST(? AS INTERVAL)
                  AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                ORDER BY next_attempt_at
                LIMIT ?
                """, Long.class, staleAfter.toSeconds() + " seconds", limit);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }

    /** Типы событий — из закрытого списка ({@link WebhookEvents}), поэтому литерал массива безопасен. */
    private static String arrayLiteral(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    private static List<String> toList(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    public record Subscription(
            long id,
            long restaurantId,
            long createdBy,
            String url,
            String description,
            List<String> eventTypes,
            String secretEnc,
            String previousSecretEnc,
            OffsetDateTime previousSecretUntil,
            boolean active,
            int consecutiveFailures,
            String disabledReason,
            OffsetDateTime createdAt
    ) {
    }

    public record Delivery(
            long id,
            long subscriptionId,
            UUID eventId,
            String eventType,
            String payload,
            String status,
            int attempts,
            Integer lastStatusCode,
            String lastError,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime createdAt,
            OffsetDateTime lastAttemptAt,
            OffsetDateTime deliveredAt
    ) {
    }
}
