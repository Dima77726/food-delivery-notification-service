package com.dima.fooddelivery.notification.service;

/**
 * Уведомление доставить нельзя, и повтор этого не изменит: получателя нет, он заблокирован,
 * у него нет адреса. Обработчик очереди не гоняет такое через пять попыток с паузами, а сразу
 * помечает уведомление неудачным с причиной.
 *
 * <p>Любое другое исключение отправителя — временное (SMTP или identity-service недоступны) и
 * уходит на повтор. Граница между ними важна в обе стороны: временный сбой, принятый за
 * постоянный, теряет письмо, а постоянный, принятый за временный, пять раз зря грузит очередь.
 */
public class PermanentDeliveryException extends RuntimeException {

    public PermanentDeliveryException(String message) {
        super(message);
    }

    public PermanentDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
