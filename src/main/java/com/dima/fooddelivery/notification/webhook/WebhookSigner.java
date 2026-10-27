package com.dima.fooddelivery.notification.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Подпись по спецификации Standard Webhooks (standardwebhooks.com) — той же схеме, что у Svix,
 * и близкой к Stripe.
 *
 * <pre>
 * webhook-id:        &lt;идентификатор события, один во всех попытках&gt;
 * webhook-timestamp: &lt;секунды Unix в момент попытки&gt;
 * webhook-signature: v1,&lt;Base64(HMAC-SHA256(ключ, id + "." + timestamp + "." + тело))&gt;
 * </pre>
 *
 * <ul>
 *     <li><b>HMAC, а не просто хеш тела:</b> подделать подпись без секрета нельзя, а секрет знают
 *     только платформа и получатель;</li>
 *     <li><b>timestamp внутри подписи:</b> получатель отвергает запросы старше нескольких минут,
 *     и перехваченный запрос нельзя проиграть заново (replay) через час;</li>
 *     <li><b>id внутри подписи:</b> его нельзя подменить, чтобы обойти дедупликацию;</li>
 *     <li><b>несколько подписей через пробел:</b> во время ротации секрета запрос подписан и
 *     новым, и старым — получатель проверяет любой, который знает.</li>
 * </ul>
 *
 * <p>Секрет — {@code whsec_} + Base64 случайных 24 байт; ключ HMAC — декодированные байты.
 */
public final class WebhookSigner {

    public static final String SECRET_PREFIX = "whsec_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private WebhookSigner() {
    }

    public static String newSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return SECRET_PREFIX + Base64.getEncoder().encodeToString(bytes);
    }

    /** Значение заголовка webhook-signature: по подписи на каждый действующий секрет. */
    public static String signatureHeader(List<String> secrets, String id, long timestamp, String body) {
        return secrets.stream()
                .map(secret -> "v1," + sign(secret, id, timestamp, body))
                .collect(Collectors.joining(" "));
    }

    static String sign(String secret, String id, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring(SECRET_PREFIX.length())), "HmacSHA256"));
            byte[] signature = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось подписать вебхук", exception);
        }
    }
}
