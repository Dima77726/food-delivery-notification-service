package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.domain.NotificationEnqueued;
import com.dima.fooddelivery.notification.persistence.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Модуль Notification.
 *
 * <p>Уведомление сначала записывается в таблицу со статусом PENDING — база здесь источник
 * правды о том, что отправить. Сразу после коммита задача уходит в RabbitMQ, в очередь своего
 * канала, и обработчик ({@code NotificationWorker}) отправляет её, повторяя при сбоях. Если
 * задача потерялась по дороге, страховочный опрос ({@code NotificationSweeper}) найдёт
 * зависшее уведомление в базе и поставит задачу заново.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Постановка уведомления по событию из Kafka.
     *
     * <p>Отдельная транзакция и никакого перехвата DuplicateKeyException: его должен увидеть
     * консьюмер, чтобы отличить повторную доставку от настоящей ошибки.
     */
    @Transactional
    public Long enqueueFromEvent(
            UUID eventId,
            Long recipientId,
            NotificationChannel channel,
            String type,
            String subject,
            String body,
            Long orderId,
            String correlationId
    ) {
        Long notificationId = notificationRepository.insert(
                eventId, recipientId, channel, type, subject, body, orderId, correlationId
        );

        // Задача в RabbitMQ уходит после коммита — см. NotificationTaskPublisher.
        eventPublisher.publishEvent(new NotificationEnqueued(notificationId, channel, type, correlationId));

        return notificationId;
    }

    @Transactional
    public Long enqueue(
            Long recipientId,
            NotificationChannel channel,
            String type,
            String subject,
            String body,
            Long orderId
    ) {
        Long notificationId = notificationRepository.insert(recipientId, channel, type, subject, body, orderId);

        eventPublisher.publishEvent(new NotificationEnqueued(notificationId, channel, type, null));

        log.debug("Уведомление поставлено в очередь: notificationId={}, type={}", notificationId, type);

        return notificationId;
    }

    /**
     * Отправляет одно уведомление.
     *
     * <p>Строка блокируется на время отправки ({@code FOR UPDATE SKIP LOCKED}): если ту же
     * задачу одновременно взял другой обработчик — например, повтор от страховочного опроса, —
     * он строку не получит и ничего не сделает. Отправленное раньше уведомление тоже не найдётся:
     * выборка берёт только PENDING. Так повтор задачи никогда не превращается в второе письмо.
     *
     * <p>Сбой отправки — исключение наружу: транзакция откатывается, блокировка снимается,
     * а что делать дальше — повторить позже или сдаться, — решает обработчик очереди.
     *
     * <p>Транзакция держится открытой, пока идёт отправка. С записью в лог это доли
     * миллисекунды; с настоящим SMTP отправку стоит вынести из транзакции, пометив строку
     * отдельным статусом «в работе».
     *
     * @return {@code true}, если уведомление отправлено сейчас; {@code false} — делать нечего
     */
    @Transactional
    public boolean deliver(Long notificationId) {
        Optional<Notification> pending = notificationRepository.lockPendingById(notificationId);

        if (pending.isEmpty()) {
            return false;
        }

        notificationSender.send(pending.get());
        notificationRepository.markSent(notificationId);

        return true;
    }

    /** Попытки кончились: уведомление больше не отправляется, причина остаётся в журнале. */
    @Transactional
    public void markFailed(Long notificationId, String reason) {
        String trimmed = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));

        notificationRepository.markFailed(notificationId, trimmed);
    }

    /** Уведомления, которые ждут отправки дольше, чем могли бы, — для страховочного опроса. */
    @Transactional(readOnly = true)
    public List<Notification> findStalePending(OffsetDateTime createdBefore, int limit) {
        return notificationRepository.findPendingCreatedBefore(createdBefore, limit);
    }

    @Transactional(readOnly = true)
    public List<Notification> getNotificationsForUser(Long userId) {
        return notificationRepository.findByRecipientId(userId);
    }

    /**
     * Канал доставки. Реализация подменяется без правки сервиса — так же, как платёжный шлюз.
     */
    public interface NotificationSender {

        void send(Notification notification);
    }
}
