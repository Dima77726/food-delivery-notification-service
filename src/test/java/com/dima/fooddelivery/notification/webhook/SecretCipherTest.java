package com.dima.fooddelivery.notification.webhook;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretCipherTest {

    private static WebhookProperties withKey(String key) {
        return new WebhookProperties(key, "g", List.of(Duration.ofSeconds(1)), 20, List.of(),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(1));
    }

    @Test
    void shouldRoundTripWithFreshIvEachTime() {
        SecretCipher cipher = new SecretCipher(withKey("MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="));

        String first = cipher.encrypt("whsec_abc");
        String second = cipher.encrypt("whsec_abc");

        assertNotEquals(first, second, "одинаковый секрет не должен давать одинаковый шифротекст");
        assertEquals("whsec_abc", cipher.decrypt(first));
    }

    @Test
    void shouldRejectTamperedCiphertext() {
        SecretCipher cipher = new SecretCipher(withKey("MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="));
        String stored = cipher.encrypt("whsec_abc");
        // Портим байт шифротекста, а не символ Base64: замена символа может попасть в биты
        // выравнивания и не изменить ни одного байта.
        byte[] bytes = java.util.Base64.getDecoder().decode(stored);
        bytes[bytes.length / 2] ^= 0x01;
        String tampered = java.util.Base64.getEncoder().encodeToString(bytes);

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void shouldSwitchWebhooksOffWithoutKeyButFailOnWrongKey() {
        SecretCipher off = new SecretCipher(withKey(""));

        assertFalse(off.isConfigured());
        assertThrows(WebhookErrors.Unavailable.class, () -> off.encrypt("x"));
        assertThrows(IllegalStateException.class, () -> new SecretCipher(withKey("c2hvcnQ=")));
    }
}
