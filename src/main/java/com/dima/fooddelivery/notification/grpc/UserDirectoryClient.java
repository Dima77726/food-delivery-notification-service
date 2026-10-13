package com.dima.fooddelivery.notification.grpc;

import java.util.Optional;

/**
 * Откуда сервис уведомлений берёт контакт получателя. Интерфейс, а не прямой вызов gRPC, чтобы
 * отправитель проверялся без сети и без identity-service.
 */
public interface UserDirectoryClient {

    /**
     * @return контакт или пусто, если такого пользователя нет — это не сбой, а ответ
     * @throws DirectoryUnavailableException если ответа нет (сеть, дедлайн, разомкнутый предохранитель)
     */
    Optional<UserContactInfo> findContact(Long userId);

    /** Спросить не удалось: уведомление нужно повторить позже, а не считать получателя несуществующим. */
    class DirectoryUnavailableException extends RuntimeException {

        public DirectoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
