package com.dima.fooddelivery.notification.webhook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookUrlGuardTest {

    private final WebhookUrlGuard guard = new WebhookUrlGuard(new WebhookProperties(
            null, "g", List.of(Duration.ofSeconds(1)), 20, List.of("webhook-sink"),
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(1)));

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "::1", "fd00::1", "fe80::1"})
    void shouldTreatInternalAddressesAsNotPublic(String ip) throws Exception {
        assertFalse(WebhookUrlGuard.isPublic(InetAddress.getByName(ip)), ip);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "100.128.0.1", "2001:4860:4860::8888"})
    void shouldTreatInternetAddressesAsPublic(String ip) throws Exception {
        assertTrue(WebhookUrlGuard.isPublic(InetAddress.getByName(ip)), ip);
    }

    @Test
    void shouldRequireHttpsAndRejectCredentialsAndLiteralInternalIps() {
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class, () -> guard.check("http://8.8.8.8/hook"));
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class, () -> guard.check("https://user:pass@8.8.8.8/hook"));
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class, () -> guard.check("https://10.0.0.1/hook"));
        assertThrows(WebhookUrlGuard.InvalidWebhookUrlException.class, () -> guard.check("ftp://webhook-sink/hook"));
        assertDoesNotThrow(() -> guard.check("https://8.8.8.8/hook"));
    }

    @Test
    void shouldAllowConfiguredInternalHostOverHttp() {
        assertEquals("webhook-sink", guard.check("http://webhook-sink:8000/hooks").getHost());
    }
}
