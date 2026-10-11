package com.dima.fooddelivery.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Разгребает очередь уведомлений.
 *
 * <p>Никакого внешнего координатора вроде ShedLock не нужно: очередь раздаётся через
 * {@code FOR UPDATE SKIP LOCKED} внутри самой выборки, поэтому несколько экземпляров
 * приложения разбирают её параллельно и не пересекаются. Планировщик при этом остаётся
 * тривиальным — вся защита от гонки живёт в одном SQL-запросе.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDispatchJob {

    private final NotificationService notificationService;

    @Scheduled(fixedDelayString = "${app.notification.dispatch-interval:PT30S}")
    public void dispatch() {
        int sent = notificationService.dispatchPending();

        if (sent > 0) {
            log.info("Отправлено уведомлений: {}", sent);
        }
    }
}
