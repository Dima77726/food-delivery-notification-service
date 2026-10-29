package com.dima.fooddelivery.notification.messenger;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class MessengerDeliveryRepository {

    private final JdbcTemplate jdbc;

    /**
     * Берёт задачу в работу: создаёт строку или захватывает ожидающую повтора. Аренда на минуту: если
     * обработчик упал посреди отправки, после её истечения задачу возьмёт следующий.
     *
     * @return номер попытки или пустое значение — задача уже отправлена, отброшена или её прямо сейчас отправляют
     */
    public Optional<Integer> claim(MessengerNotification task) {
        jdbc.update("""
                INSERT INTO messenger_delivery (delivery_key, user_id, kind) VALUES (?, ?, ?) ON CONFLICT (delivery_key) DO NOTHING
                """, task.key(), task.userId(), task.kind());
        return jdbc.queryForList("""
                UPDATE messenger_delivery SET status = 'SENDING', attempts = attempts + 1, lease_until = CURRENT_TIMESTAMP + INTERVAL '1 minute'
                WHERE delivery_key = ? AND (status = 'PENDING' OR (status = 'SENDING' AND lease_until < CURRENT_TIMESTAMP))
                RETURNING attempts
                """, Integer.class, task.key()).stream().findFirst();
    }

    public void finish(String key, String status, String error) {
        jdbc.update("""
                UPDATE messenger_delivery SET status = ?, last_error = ?, lease_until = NULL,
                       sent_at = CASE WHEN ? = 'SENT' THEN CURRENT_TIMESTAMP ELSE sent_at END
                WHERE delivery_key = ?
                """, status, error == null ? null : error.substring(0, Math.min(500, error.length())), status, key);
    }

    /** Вернуть в ожидание повтора; попытка, отложенная по просьбе Telegram (retry_after), попыткой не считается. */
    public void release(String key, String error, boolean countAttempt) {
        jdbc.update("""
                UPDATE messenger_delivery SET status = 'PENDING', lease_until = NULL, last_error = ?,
                       attempts = attempts - CASE WHEN ? THEN 0 ELSE 1 END
                WHERE delivery_key = ?
                """, error == null ? null : error.substring(0, Math.min(500, error.length())), countAttempt, key);
    }
}
