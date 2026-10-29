package com.dima.fooddelivery.notification.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Привязка Telegram-аккаунта к пользователю.
 *
 * <p>Бот не знает, кто ему пишет: Telegram сообщает номер чата, а не пользователя платформы. Связь
 * устанавливается кодом. Залогиненный пользователь просит код ({@link #issueLinkCode}), получает
 * ссылку {@code t.me/<бот>?start=<код>}, открывает её в Telegram — и бот получает {@code /start <код>}
 * из чата, который теперь можно записать на этого пользователя ({@link #completeLink}).
 *
 * <p>Код одноразовый, живёт {@code linkTtl} и хранится только в виде SHA-256: его выдают человеку
 * в ссылке, и утечка таблицы не должна позволять привязать чужой аккаунт. Случайные 128 бит
 * не подобрать, поэтому хэш без соли здесь уместен.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class TelegramLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TelegramLinkRepository repository;
    private final TelegramProperties properties;

    /**
     * Выдаёт новый код привязки; предыдущий перестаёт работать.
     *
     * @return ссылка для открытия в Telegram и срок её действия
     */
    @Transactional
    public LinkOffer issueLinkCode(Long userId) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        // URL-безопасный алфавит без «=»: параметр start у Telegram допускает только [A-Za-z0-9_-].
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        OffsetDateTime expiresAt = OffsetDateTime.now().plus(properties.linkTtl());
        repository.saveCode(userId, hash(code), expiresAt);

        log.info("Выдан код привязки Telegram: userId={}", userId);

        return new LinkOffer("https://t.me/" + properties.botUsername() + "?start=" + code, expiresAt);
    }

    /**
     * Завершает привязку по коду из {@code /start}.
     *
     * @return {@code true}, если код подошёл и чат привязан; {@code false} — код неизвестен, истёк или уже использован
     */
    @Transactional
    public boolean completeLink(String code, long chatId) {
        Optional<Long> userId = repository.consumeCode(hash(code));

        if (userId.isEmpty()) {
            log.info("Код привязки Telegram не подошёл: chatId={}", chatId);
            return false;
        }

        repository.link(userId.get(), chatId);
        repository.clearUnlinkReason(userId.get());

        log.info("Telegram привязан: userId={}", userId.get());

        return true;
    }

    @Transactional(readOnly = true)
    public Optional<Long> chatIdOf(Long userId) {
        return repository.findChatId(userId);
    }

    @Transactional(readOnly = true)
    public boolean isLinked(Long userId) {
        return repository.findChatId(userId).isPresent();
    }

    @Transactional(readOnly = true)
    public Optional<OffsetDateTime> linkedAt(Long userId) {
        return repository.findLinkedAt(userId);
    }

    @Transactional
    public boolean unlinkUser(Long userId) {
        boolean removed = repository.unlinkUser(userId);

        if (removed) {
            log.info("Telegram отвязан пользователем: userId={}", userId);
        }

        return removed;
    }

    @Transactional
    public boolean unlinkChat(long chatId) {
        boolean removed = repository.unlinkChat(chatId);

        if (removed) {
            log.info("Telegram отвязан из чата: chatId={}", chatId);
        }

        return removed;
    }

    /**
     * Чат недоступен не по воле пользователя: бота заблокировали (403) или чат удалён. Привязка снимается, а
     * причина сохраняется — в настройках человек увидит, почему уведомления перестали приходить.
     */
    @Transactional
    public void unlinkUnreachable(long chatId, String reason) {
        repository.unlinkChatReturningUser(chatId).ifPresent(userId -> {
            repository.saveUnlinkReason(userId, reason);
            log.info("Telegram отвязан: чат недоступен, userId={}, reason={}", userId, reason);
        });
    }

    @Transactional(readOnly = true)
    public Optional<String> unlinkReason(Long userId) {
        return repository.findUnlinkReason(userId);
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 обязан быть в любой JVM; если его нет, это сломанная среда, а не ошибка данных.
            throw new IllegalStateException(exception);
        }
    }

    /**
     * @param deepLink ссылка, которую пользователь открывает в Telegram
     */
    public record LinkOffer(String deepLink, OffsetDateTime expiresAt) {
    }
}
