package com.dima.fooddelivery.notification.live;

import com.dima.fooddelivery.notification.api.NotificationResponse;

import java.util.List;

/**
 * Ответ долгого запроса.
 *
 * @param items     новые уведомления, старые первыми; пустой список — таймаут, нового нет
 * @param nextAfter курсор для следующего запроса: передать его в {@code after} как есть
 */
public record NotificationPollResponse(List<NotificationResponse> items, long nextAfter) {

    static NotificationPollResponse empty(long afterId) {
        return new NotificationPollResponse(List.of(), afterId);
    }
}
