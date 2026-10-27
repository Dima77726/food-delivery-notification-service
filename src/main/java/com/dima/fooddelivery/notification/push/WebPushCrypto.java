package com.dima.fooddelivery.notification.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Криптография Web Push на JDK, без библиотек: шифрование сообщения (RFC 8291 + RFC 8188, aes128gcm)
 * и подпись VAPID (RFC 8292).
 *
 * <p><b>Зачем шифровать.</b> Сообщение идёт через push-сервис браузера (FCM у Chrome, Mozilla у
 * Firefox, Apple у Safari) — посредника, которому содержимое знать незачем. Браузер при подписке
 * отдаёт свой открытый ключ ({@code p256dh}) и общий секрет ({@code auth}); прочитать сообщение
 * сможет только он.
 *
 * <pre>
 * эфемерная пара (as) ── ECDH с ключом браузера (ua) ──▶ общий секрет
 * HKDF(auth, общий секрет, "WebPush: info"‖ua‖as)    ──▶ IKM
 * HKDF(salt, IKM)                                    ──▶ ключ AES-128 (CEK) и nonce
 * тело = salt(16) ‖ rs(4) ‖ len(1) ‖ as(65) ‖ AES-GCM(CEK, nonce, текст ‖ 0x02)
 * </pre>
 *
 * <p>Эфемерная пара и salt — новые на каждое сообщение: одинаковый текст не даёт одинакового тела.
 *
 * <p><b>VAPID</b> — «кто я» для push-сервиса: JWT, подписанный ключом приложения (ES256), с
 * {@code aud} = адрес push-сервиса. Открытый ключ VAPID браузер получил при подписке, поэтому
 * push-сервис примет сообщения для этой подписки только от владельца закрытого ключа.
 */
public final class WebPushCrypto {

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final Base64.Encoder B64URL_OUT = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RECORD_SIZE = 4096;
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {
    }

    /** Шифрование со свежими эфемерным ключом и salt. */
    public static byte[] encrypt(String uaPublicB64, String authSecretB64, byte[] plaintext) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(uaPublicB64, authSecretB64, plaintext, generateKeyPair(), salt);
    }

    /** То же с заданными ключом и salt — для проверки по примеру из RFC 8291. */
    static byte[] encrypt(String uaPublicB64, String authSecretB64, byte[] plaintext, KeyPair asKeys, byte[] salt) {
        try {
            byte[] uaPublic = B64URL.decode(uaPublicB64);
            byte[] authSecret = B64URL.decode(authSecretB64);
            byte[] asPublic = rawPublic((ECPublicKey) asKeys.getPublic());

            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(asKeys.getPrivate());
            agreement.doPhase(publicKey(uaPublic), true);
            byte[] ecdhSecret = agreement.generateSecret();

            byte[] keyInfo = concat("WebPush: info".getBytes(StandardCharsets.US_ASCII), new byte[]{0}, uaPublic, asPublic);
            byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);

            byte[] cek = hkdf(salt, ikm, concat("Content-Encoding: aes128gcm".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 16);
            byte[] nonce = hkdf(salt, ikm, concat("Content-Encoding: nonce".getBytes(StandardCharsets.US_ASCII), new byte[]{0}), 12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
            // 0x02 — разделитель последней (и единственной) записи; дополнения (padding) нет.
            byte[] ciphertext = cipher.doFinal(concat(plaintext, new byte[]{2}));

            ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length)
                    .put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
            return concat(header.array(), ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось зашифровать push-сообщение", exception);
        }
    }

    /**
     * Заголовок {@code Authorization: vapid t=<JWT>, k=<открытый ключ>}.
     *
     * @param audience origin push-сервиса: {@code https://fcm.googleapis.com}
     */
    public static String vapidAuthorization(String audience, String subject, String publicKeyB64, String privateKeyB64, long expiresAtEpochSecond) {
        try {
            String header = B64URL_OUT.encodeToString("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
            String claims = B64URL_OUT.encodeToString(("{\"aud\":\"" + audience + "\",\"exp\":" + expiresAtEpochSecond
                    + ",\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
            String signingInput = header + "." + claims;

            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey(B64URL.decode(privateKeyB64)));
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String signature = B64URL_OUT.encodeToString(derToJose(signer.sign()));

            return "vapid t=" + signingInput + "." + signature + ", k=" + publicKeyB64;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Не удалось подписать VAPID", exception);
        }
    }

    /** Пара VAPID в Base64url: [открытый (65 байт), закрытый (32 байта)]. */
    public static String[] newVapidKeys() {
        KeyPair pair = generateKeyPair();
        return new String[]{
                B64URL_OUT.encodeToString(rawPublic((ECPublicKey) pair.getPublic())),
                B64URL_OUT.encodeToString(unsigned(((ECPrivateKey) pair.getPrivate()).getS(), 32))
        };
    }

    // ---------- HKDF (RFC 5869) с одним блоком вывода: длины здесь ≤ 32 байт ----------

    private static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        byte[] prk = hmac(salt, ikm);
        return Arrays.copyOf(hmac(prk, concat(info, new byte[]{1})), length);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // ---------- Ключи P-256 в «сыром» виде, как их отдаёт браузер ----------

    static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static KeyPair keyPair(String publicB64, String privateB64) throws GeneralSecurityException {
        return new KeyPair(publicKey(B64URL.decode(publicB64)), privateKey(B64URL.decode(privateB64)));
    }

    private static ECPublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        if (raw.length != 65 || raw[0] != 4) {
            throw new GeneralSecurityException("Ожидался несжатый ключ P-256 (65 байт, 0x04…)");
        }
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 33)), new BigInteger(1, Arrays.copyOfRange(raw, 33, 65)));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, P256));
    }

    private static ECPrivateKey privateKey(byte[] raw) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, raw), P256));
    }

    private static byte[] rawPublic(ECPublicKey key) {
        return concat(new byte[]{4}, unsigned(key.getW().getAffineX(), 32), unsigned(key.getW().getAffineY(), 32));
    }

    private static byte[] unsigned(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == length) {
            return bytes;
        }
        byte[] out = new byte[length];
        int copy = Math.min(bytes.length, length);
        System.arraycopy(bytes, bytes.length - copy, out, length - copy, copy);
        return out;
    }

    /** JDK подписывает ECDSA в DER (SEQUENCE{r, s}), а JWT ждёт r‖s по 32 байта. */
    private static byte[] derToJose(byte[] der) {
        int offset = der[1] == (byte) 0x81 ? 3 : 2;
        int rLength = der[offset + 1];
        byte[] r = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        int sOffset = offset + 2 + rLength;
        int sLength = der[sOffset + 1];
        byte[] s = Arrays.copyOfRange(der, sOffset + 2, sOffset + 2 + sLength);
        return concat(unsigned(new BigInteger(1, r), 32), unsigned(new BigInteger(1, s), 32));
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
