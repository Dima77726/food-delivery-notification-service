package com.dima.fooddelivery.notification.messaging;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Запускает страховочный опрос по расписанию. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationSweepJob {

    private final NotificationSweeper sweeper;

    @Scheduled(fixedDelayString = "${app.notification.sweep-interval:PT1M}")
    public void sweep() {
        sweeper.sweep();
    }
}
