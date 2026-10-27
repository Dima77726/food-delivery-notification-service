package com.dima.fooddelivery.notification.webhook;

/**
 * Права на ресторан для API вебхуков. Интерфейс, а не сразу gRPC-клиент: тестам незачем поднимать
 * identity-service, чтобы проверить вебхуки.
 */
public interface RestaurantAccess {

    /**
     * @return идентификатор текущего пользователя
     * @throws WebhookErrors.Forbidden если он рестораном не управляет (администратору можно всё)
     */
    long requireManager(long restaurantId);
}
