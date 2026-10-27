package com.dima.fooddelivery.notification.push;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Настройки Web Push.
 *
 * @param vapidPublicKey  открытый ключ VAPID (P-256, 65 байт в Base64url) — его получает браузер при подписке
 * @param vapidPrivateKey закрытый ключ VAPID (32 байта в Base64url). Без пары ключей push выключен
 * @param subject         контакт для push-сервиса (mailto: или https:) — по нему с вами свяжутся при проблемах
 * @param ttl             сколько push-сервис хранит сообщение для выключенного браузера
 * @param allowedHosts    суффиксы хостов push-сервисов. Адрес подписки присылает браузер, то есть по
 *                        сути пользователь: без списка это была бы та же дыра SSRF, что у вебхуков
 */
@ConfigurationProperties(prefix = "app.notification.push")
public record WebPushProperties(
        String vapidPublicKey,
        String vapidPrivateKey,
        @DefaultValue("mailto:admin@food-delivery.local") String subject,
        @DefaultValue("PT24H") Duration ttl,
        @DefaultValue({"fcm.googleapis.com", "push.services.mozilla.com", "notify.windows.com", "push.apple.com"})
        List<String> allowedHosts
) {

    public boolean configured() {
        return vapidPublicKey != null && !vapidPublicKey.isBlank() && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
    }
}
