package com.dima.fooddelivery.notification.webhook;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Страховочный опрос: доставки, чья очередная попытка запоздала (сообщение потерялось между
 * коммитом и брокером, брокер перезапускался без сохранения), ставятся в очередь заново.
 * Двойная постановка безопасна — отправит только тот, кто возьмёт аренду.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookSweepJob {

    private static final int BATCH = 100;

    private final WebhookRepository repository;
    private final WebhookTaskPublisher publisher;
    private final WebhookProperties properties;

    @Scheduled(fixedDelayString = "${app.webhooks.sweep-interval:PT1M}")
    void sweep() {
        List<Long> stale = repository.findStalePending(properties.sweepStaleAfter(), BATCH);
        if (!stale.isEmpty()) {
            log.info("Вебхуки: {} запоздавших доставок поставлены заново", stale.size());
            stale.forEach(publisher::dispatch);
        }
    }
}
