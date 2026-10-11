package com.dima.fooddelivery.notification.messaging;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Страховочный опрос: находит уведомления, застрявшие в PENDING, и ставит задачу заново.
 *
 * <p>Раньше этот же планировщик и был рассылкой — раз в 30 секунд забирал пачку из базы.
 * Теперь рассылку ведёт RabbitMQ, а опрос ловит только то, что мимо него прошло: брокер
 * не подтвердил публикацию, процесс упал между коммитом и публикацией. Поэтому и порог
 * большой — дольше, чем уведомление могло бы законно ждать в цепочке повторов, — и запускается
 * опрос редко.
 *
 * <p>Повторная задача для уведомления, которое ещё в работе, безвредна: обработчик не получит
 * заблокированную строку и не найдёт отправленную — см. {@link NotificationService#deliver}.
 */
@Slf4j
@Component
public class NotificationSweeper {

    private static final int BATCH_SIZE = 100;

    private final NotificationService notificationService;
    private final NotificationTaskPublisher publisher;
    private final Duration staleAfter;

    public NotificationSweeper(
            NotificationService notificationService,
            NotificationTaskPublisher publisher,
            @Value("${app.notification.sweep-stale-after:PT5M}") Duration staleAfter
    ) {
        this.notificationService = notificationService;
        this.publisher = publisher;
        this.staleAfter = staleAfter;
    }

    /**
     * Расписанием занимается {@link NotificationSweepJob}: свойство {@code sweep-enabled} гасит
     * только таймер, а тесты вызывают опрос вручную.
     */
    public void sweep() {
        List<Notification> stale = notificationService.findStalePending(OffsetDateTime.now().minus(staleAfter), BATCH_SIZE);

        int republished = 0;
        for (Notification notification : stale) {
            if (publisher.publish(notification.id(), notification.channel(), notification.type(), null)) {
                republished++;
            }
        }

        if (republished > 0) {
            log.warn("Поставлены заново задачи для зависших уведомлений: {}", republished);
        }
    }
}
