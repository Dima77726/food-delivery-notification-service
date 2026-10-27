package com.dima.fooddelivery.notification.push;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPushCryptoTest {

    /**
     * Пример из RFC 8291, приложение A: те же ключи, секрет и salt обязаны дать ровно то тело,
     * что в RFC. Это проверка против спецификации, а не против самого себя.
     */
    @Test
    void shouldMatchRfc8291Example() throws Exception {
        String plaintext = "When I grow up, I want to be a watermelon";
        var asKeys = WebPushCrypto.keyPair(
                "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8",
                "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw");
        byte[] salt = Base64.getUrlDecoder().decode("DGv6ra1nlYgDCS1FRnbzlw");

        byte[] body = WebPushCrypto.encrypt(
                "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4",
                "BTBZMqHH6r4Tts7J_aSIgg",
                plaintext.getBytes(StandardCharsets.UTF_8), asKeys, salt);

        assertEquals(
                "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN",
                Base64.getUrlEncoder().withoutPadding().encodeToString(body));
    }

    @Test
    void shouldSignVapidJwtVerifiableWithPublicKey() throws Exception {
        String[] keys = WebPushCrypto.newVapidKeys();

        String header = WebPushCrypto.vapidAuthorization("https://fcm.googleapis.com", "mailto:admin@example.test", keys[0], keys[1], 1_900_000_000L);

        assertTrue(header.startsWith("vapid t="));
        assertTrue(header.endsWith(", k=" + keys[0]));
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(claims.contains("\"aud\":\"https://fcm.googleapis.com\""));

        // Подпись r‖s (64 байта) переводится обратно в DER и проверяется стандартным JDK.
        byte[] raw = Base64.getUrlDecoder().decode(parts[2]);
        assertEquals(64, raw.length);
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki(Base64.getUrlDecoder().decode(keys[0])))));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(raw), "подпись VAPID должна проверяться открытым ключом");
    }

    /** Сырой ключ P-256 → SubjectPublicKeyInfo: фиксированный префикс DER плюс 65 байт точки. */
    private static byte[] spki(byte[] raw) {
        byte[] prefix = Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgA=");
        byte[] out = new byte[prefix.length + raw.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(raw, 0, out, prefix.length, raw.length);
        return out;
    }
}
