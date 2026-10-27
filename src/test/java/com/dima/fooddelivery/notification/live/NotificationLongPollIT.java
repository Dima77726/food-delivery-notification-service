package com.dima.fooddelivery.notification.live;

import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.service.NotificationService;
import com.dima.fooddelivery.notification.support.TestJwt;
import com.dima.fooddelivery.notification.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Long polling ленты уведомлений через настоящий RabbitMQ: запись уведомления, толчок через
 * fanout-обмен после коммита, ответ ждущему запросу.
 */
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "app.notification.sweep-enabled=false",
        "logging.level.org.testcontainers=WARN",
        "logging.level.com.zaxxer.hikari=WARN"
})
@Import(TestcontainersConfiguration.class)
class NotificationLongPollIT {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestJwt jwt;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationPollWaiters waiters;

    @Test
    void shouldAnswerImmediatelyWhenThereIsSomethingNewerThanCursor() throws Exception {
        long userId = jwt.newUserId();
        Long id = notificationService.enqueue(userId, NotificationChannel.EMAIL, "ORDER_PAID", "Заказ", "Оплачен", 1L);

        MvcResult started = mockMvc.perform(get("/api/v1/notifications/me/poll")
                        .param("after", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.tokenFor(userId, "CUSTOMER")))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.nextAfter").value(id));
    }

    @Test
    void shouldWakeWaitingRequestWhenNotificationArrives() throws Exception {
        long userId = jwt.newUserId();
        Long first = notificationService.enqueue(userId, NotificationChannel.EMAIL, "ORDER_PAID", "Заказ", "Оплачен", 1L);

        MvcResult waiting = mockMvc.perform(get("/api/v1/notifications/me/poll")
                        .param("after", String.valueOf(first))
                        .param("timeoutSeconds", "20")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.tokenFor(userId, "CUSTOMER")))
                .andExpect(request().asyncStarted())
                .andReturn();
        awaitTrue(() -> waiters.waiting(userId) == 1, "запрос не встал в ожидание");

        Instant enqueued = Instant.now();
        Long second = notificationService.enqueue(userId, NotificationChannel.EMAIL, "ORDER_ACCEPTED", "Заказ", "Принят", 1L);

        waiting.getAsyncResult(WAIT.toMillis());
        assertTrue(Duration.between(enqueued, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0,
                "ответ должен прийти по толчку, а не по таймауту");

        mockMvc.perform(asyncDispatch(waiting))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.nextAfter").value(second));
    }

    @Test
    void shouldNotWakeRequestOfAnotherUser() throws Exception {
        long userId = jwt.newUserId();
        long otherId = jwt.newUserId();

        MvcResult waiting = mockMvc.perform(get("/api/v1/notifications/me/poll")
                        .param("timeoutSeconds", "20")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.tokenFor(userId, "CUSTOMER")))
                .andExpect(request().asyncStarted())
                .andReturn();
        awaitTrue(() -> waiters.waiting(userId) == 1, "запрос не встал в ожидание");

        notificationService.enqueue(otherId, NotificationChannel.EMAIL, "ORDER_PAID", "Заказ", "Оплачен", 1L);
        Thread.sleep(1000);

        assertTrue(waiters.waiting(userId) == 1, "чужое уведомление не должно отвечать на запрос");

        // Таймаут DeferredResult в MockMvc запускает не контейнер, а тест.
        for (var listener : ((MockAsyncContext) waiting.getRequest().getAsyncContext()).getListeners()) {
            listener.onTimeout(null);
        }

        mockMvc.perform(asyncDispatch(waiting))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextAfter").value(0));
    }

    @Test
    void shouldRejectAnonymousPoll() throws Exception {
        mockMvc.perform(get("/api/v1/notifications/me/poll"))
                .andExpect(status().isUnauthorized());
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message) throws InterruptedException {
        Instant deadline = Instant.now().plus(WAIT);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(message);
    }
}
