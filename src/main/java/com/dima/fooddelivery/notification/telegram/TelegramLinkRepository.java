package com.dima.fooddelivery.notification.telegram;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Привязки Telegram, коды привязки и позиция опроса бота — рукописный SQL, как и остальное
 * хранилище сервиса.
 */
@Repository
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class TelegramLinkRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Сохраняет код привязки пользователя, заменяя прежний: актуальный код один, старая ссылка
     * после выдачи новой перестаёт работать.
     */
    public void saveCode(Long userId, String codeHash, OffsetDateTime expiresAt) {
        jdbc.update(
                """
                        INSERT INTO telegram_link_code (user_id, code_hash, expires_at)
                        VALUES (:userId, :codeHash, :expiresAt)
                        ON CONFLICT (user_id) DO UPDATE
                            SET code_hash = EXCLUDED.code_hash,
                                expires_at = EXCLUDED.expires_at
                        """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("codeHash", codeHash)
                        .addValue("expiresAt", expiresAt)
        );
    }

    /**
     * Расходует код: удаляет и возвращает пользователя, если код есть и не истёк. Одним
     * оператором {@code DELETE … RETURNING}, чтобы два одновременных {@code /start} с одним кодом
     * не привязали его дважды — выигрывает ровно один.
     */
    public Optional<Long> consumeCode(String codeHash) {
        return jdbc.query(
                """
                        DELETE FROM telegram_link_code
                        WHERE code_hash = :codeHash
                          AND expires_at > CURRENT_TIMESTAMP
                        RETURNING user_id
                        """,
                new MapSqlParameterSource("codeHash", codeHash),
                (rs, rowNum) -> rs.getLong("user_id")
        ).stream().findFirst();
    }

    /**
     * Привязывает чат к пользователю. Прежняя привязка пользователя заменяется, а чат, который
     * принадлежал другому, у того снимается: один чат — один пользователь, иначе чужие
     * уведомления уходили бы в чужую переписку.
     */
    public void link(Long userId, long chatId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("chatId", chatId);

        jdbc.update("DELETE FROM telegram_link WHERE chat_id = :chatId AND user_id <> :userId", params);
        jdbc.update(
                """
                        INSERT INTO telegram_link (user_id, chat_id)
                        VALUES (:userId, :chatId)
                        ON CONFLICT (user_id) DO UPDATE
                            SET chat_id = EXCLUDED.chat_id,
                                linked_at = CURRENT_TIMESTAMP
                        """,
                params
        );
    }

    public Optional<Long> findChatId(Long userId) {
        return jdbc.query(
                "SELECT chat_id FROM telegram_link WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> rs.getLong("chat_id")
        ).stream().findFirst();
    }

    public Optional<OffsetDateTime> findLinkedAt(Long userId) {
        return jdbc.query(
                "SELECT linked_at FROM telegram_link WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId),
                (rs, rowNum) -> rs.getObject("linked_at", OffsetDateTime.class)
        ).stream().findFirst();
    }

    public boolean unlinkUser(Long userId) {
        return jdbc.update("DELETE FROM telegram_link WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId)) > 0;
    }

    public boolean unlinkChat(long chatId) {
        return jdbc.update("DELETE FROM telegram_link WHERE chat_id = :chatId",
                new MapSqlParameterSource("chatId", chatId)) > 0;
    }

    /** С какого {@code update_id} продолжать опрос; 0, если опрос ещё не начинался. */
    public long loadNextOffset() {
        return jdbc.query(
                "SELECT next_offset FROM telegram_poll_state WHERE id = 1",
                (rs, rowNum) -> rs.getLong("next_offset")
        ).stream().findFirst().orElse(0L);
    }

    public void saveNextOffset(long nextOffset) {
        jdbc.update(
                """
                        INSERT INTO telegram_poll_state (id, next_offset)
                        VALUES (1, :nextOffset)
                        ON CONFLICT (id) DO UPDATE SET next_offset = EXCLUDED.next_offset
                        """,
                new MapSqlParameterSource("nextOffset", nextOffset)
        );
    }
}
