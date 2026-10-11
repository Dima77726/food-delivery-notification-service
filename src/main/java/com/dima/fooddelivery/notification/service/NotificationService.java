package com.dima.fooddelivery.notification.service;

import com.dima.fooddelivery.notification.domain.Notification;
import com.dima.fooddelivery.notification.domain.NotificationChannel;
import com.dima.fooddelivery.notification.persistence.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Модуль Notification.
 *
 * <p>Уведомление сначала кладётся в таблицу со статусом PENDING в той же транзакции, что и
 * бизнес-операция, и только потом отправляется отдельным шагом. Это паттерн outbox, и он решает
 * конкретную проблему: если отправлять письмо прямо в транзакции, то при её откате письмо
 * уже улетело, а заказа нет. Обратный порядок — отправка после коммита без записи в базу —
 * теряет уведомления при падении процесса.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    /**
     * Размер пачки. Чем он больше, тем дольше держатся блокировки строк, поэтому не сотни.
     */
    private static final int SEND_BATCH_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final NotificationSender notificationSender;

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
        return notificationRepository.insert(
                eventId, recipientId, channel, type, subject, body, orderId, correlationId
        );
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

        log.debug("Уведомление поставлено в очередь: notificationId={}, type={}", notificationId, type);

        return notificationId;
    }

    /**
     * Отправляет накопившиеся уведомления.
     *
     * <p>Вся пачка обрабатывается в одной транзакции, и это обязательное условие: блокировка
     * из {@code FOR UPDATE SKIP LOCKED} живёт ровно до её конца. Отпусти транзакцию раньше —
     * и соседний экземпляр приложения подхватит те же строки.
     *
     * <p>Падение отправки одного уведомления не откатывает остальные: исключение
     * перехватывается и превращается в статус FAILED, то есть транзакция остаётся успешной.
     *
     * <p>Ограничение, которое надо знать: транзакция держится открытой всё время отправки.
     * С записью в лог это доли миллисекунды, с реальным SMTP — секунды на каждое письмо,
     * и такую транзакцию держать нельзя. Правильное решение для настоящего отправителя —
     * разделить на два шага: короткая транзакция помечает пачку как взятую в работу
     * (нужен отдельный статус PROCESSING), отправка идёт уже вне транзакции. Пока
     * отправитель пишет в лог, усложнять незачем.
     */
    @Transactional
    public int dispatchPending() {
        List<Notification> pending = notificationRepository.lockPending(SEND_BATCH_SIZE);

        int sent = 0;
        for (Notification notification : pending) {
            if (dispatchOne(notification)) {
                sent++;
            }
        }

        return sent;
    }

    private boolean dispatchOne(Notification notification) {
        try {
            notificationSender.send(notification);
            notificationRepository.markSent(notification.id());

            return true;
        } catch (RuntimeException exception) {
            log.warn(
                    "Не удалось отправить уведомление: notificationId={}, reason={}",
                    notification.id(),
                    exception.getMessage()
            );

            notificationRepository.markFailed(notification.id(), exception.getMessage());

            return false;
        }
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
