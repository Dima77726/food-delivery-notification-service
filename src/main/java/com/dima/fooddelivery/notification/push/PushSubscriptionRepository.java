package com.dima.fooddelivery.notification.push;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class PushSubscriptionRepository {

    private final JdbcTemplate jdbc;

    /** Повторная подписка того же браузера (тот же endpoint) обновляет ключи и владельца. */
    public void upsert(long userId, String endpoint, String p256dh, String auth, String userAgent) {
        jdbc.update("""
                INSERT INTO push_subscription (user_id, endpoint, p256dh, auth, user_agent)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (endpoint) DO UPDATE
                SET user_id = EXCLUDED.user_id, p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent
                """, userId, endpoint, p256dh, auth, userAgent);
    }

    public List<Subscription> findByUser(long userId) {
        return jdbc.query("SELECT id, endpoint, p256dh, auth FROM push_subscription WHERE user_id = ?",
                (rs, n) -> new Subscription(rs.getLong("id"), rs.getString("endpoint"), rs.getString("p256dh"), rs.getString("auth")),
                userId);
    }

    public boolean hasAny(long userId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM push_subscription WHERE user_id = ?)", Boolean.class, userId);
        return Boolean.TRUE.equals(exists);
    }

    public int deleteForUser(long userId, String endpoint) {
        return jdbc.update("DELETE FROM push_subscription WHERE user_id = ? AND endpoint = ?", userId, endpoint);
    }

    /**
     * Своя транзакция: умершую подписку удаляет отправка, которая затем может закончиться исключением
     * (ни один браузер не принял) — и откат транзакции доставки вернул бы подписку на место.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(long id) {
        jdbc.update("DELETE FROM push_subscription WHERE id = ?", id);
    }

    public void markSuccess(long id) {
        jdbc.update("UPDATE push_subscription SET last_success_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    public record Subscription(long id, String endpoint, String p256dh, String auth) {
    }
}
