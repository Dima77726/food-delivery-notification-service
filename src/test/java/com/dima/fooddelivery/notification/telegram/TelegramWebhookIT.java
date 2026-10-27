package com.dima.fooddelivery.notification.telegram;

import com.dima.fooddelivery.notification.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Бот в режиме вебхука: обновление приходит POST-запросом, проверяется секретный заголовок,
 * повтор того же обновления не обрабатывается дважды.
 *
 * <p>API Telegram в тесте недоступен (порт 1): регистрация вебхука и ответ пользователю падают,
 * и это заодно проверяет, что их сбой не мешает ни старту, ни приёму.
 */
@SpringBootTest(properties = {
        "app.notification.telegram.enabled=true",
        "app.notification.telegram.bot-token=000000:test-token",
        "app.notification.telegram.bot-username=food_delivery_test_bot",
        "app.notification.telegram.mode=webhook",
        "app.notification.telegram.webhook-secret=" + TelegramWebhookIT.SECRET,
        "app.notification.telegram.webhook-public-url=https://example.test",
        "app.notification.telegram.api-base-url=http://localhost:1",
        "app.notification.telegram.connect-timeout=200ms",
        "app.notification.sweep-enabled=false",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class TelegramWebhookIT {

    static final String SECRET = "test_webhook_secret-123";
    private static final AtomicLong USERS = new AtomicLong(System.nanoTime() % 1_000_000_000L + 8_000_000_000L);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TelegramLinkService links;

    @Autowired
    private TelegramLinkRepository repository;

    @Test
    void shouldLinkChatFromWebhookUpdateAndIgnoreItsRepeat() throws Exception {
        long user = USERS.incrementAndGet();
        String deepLink = links.issueLinkCode(user).deepLink();
        String code = deepLink.substring(deepLink.indexOf("start=") + "start=".length());
        long updateId = repository.loadNextOffset() + 1_000;

        String update = update(updateId, 77_001L, "/start " + code);
        receive(update, SECRET).andExpect(status().isOk());
        receive(update, SECRET).andExpect(status().isOk());

        assertEquals(77_001L, links.chatIdOf(user).orElseThrow());
        assertEquals(updateId + 1, repository.loadNextOffset());
    }

    @Test
    void shouldRejectUpdateWithoutOrWithWrongSecret() throws Exception {
        long user = USERS.incrementAndGet();
        String deepLink = links.issueLinkCode(user).deepLink();
        String code = deepLink.substring(deepLink.indexOf("start=") + "start=".length());
        String update = update(repository.loadNextOffset() + 2_000, 77_002L, "/start " + code);

        receive(update, null).andExpect(status().isUnauthorized());
        receive(update, "guess").andExpect(status().isUnauthorized());

        assertFalse(links.isLinked(user), "поддельное обновление не должно привязать чат");
        assertTrue(links.completeLink(code, 77_003L), "код после отклонённых запросов остаётся действительным");
    }

    private org.springframework.test.web.servlet.ResultActions receive(String body, String secret) throws Exception {
        var request = post(TelegramWebhookController.PATH).contentType(MediaType.APPLICATION_JSON).content(body);
        if (secret != null) {
            request.header(TelegramWebhookController.SECRET_HEADER, secret);
        }
        return mockMvc.perform(request);
    }

    private static String update(long updateId, long chatId, String text) {
        return """
                {"update_id":%d,"message":{"message_id":1,"chat":{"id":%d,"type":"private"},"date":1730000000,"text":"%s"}}
                """.formatted(updateId, chatId, text);
    }
}
