package com.dima.fooddelivery.notification.webhook;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Шифрование секретов подписок в базе: AES-256-GCM.
 *
 * <p><b>Почему шифр, а не хеш.</b> Пароль или ключ API достаточно сверить — для этого хватает
 * хеша. Секретом вебхука сервис <em>подписывает</em> каждый запрос, значит, ему нужен сам секрет.
 * Шифрование защищает от утечки дампа базы: без ключа из окружения секреты в нём бесполезны.
 *
 * <p><b>GCM</b> — шифр с проверкой целостности: подменённый в базе шифротекст не расшифруется в
 * «чужой» секрет, а даст ошибку. Вектор инициализации (12 байт) случайный на каждую запись и
 * хранится перед шифротекстом; повторять его с тем же ключом в GCM нельзя.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    /**
     * Без ключа вебхуки выключены, а не весь сервис: уведомления клиентам от них не зависят.
     * Так новый образ можно выкатить раньше, чем в окружение добавят ключ, — порядок выкатки
     * сервиса и конфигурации не связан (см. учебник, глава 3, «безопасная выкатка»).
     * Неверный ключ — другое дело: это ошибка конфигурации, и старт падает.
     */
    public SecretCipher(WebhookProperties properties) {
        if (properties.secretKey() == null || properties.secretKey().isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw = Base64.getDecoder().decode(properties.secretKey().strip());
        if (raw.length != 32) {
            throw new IllegalStateException("WEBHOOK_SECRET_KEY должен быть 32 байтами в Base64, а не " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireConfigured();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось зашифровать секрет", exception);
        }
    }

    public String decrypt(String stored) {
        requireConfigured();
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось расшифровать секрет: другой ключ или повреждённая запись", exception);
        }
    }

    private void requireConfigured() {
        if (key == null) {
            throw new WebhookErrors.Unavailable("Вебхуки выключены: не задан WEBHOOK_SECRET_KEY");
        }
    }
}
