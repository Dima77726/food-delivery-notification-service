package com.dima.fooddelivery.notification.push;

import com.dima.fooddelivery.notification.config.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Подписка браузера на Web Push. Браузер получает открытый ключ VAPID, спрашивает разрешение у
 * человека, подписывается в своём push-сервисе и присылает сюда адрес и ключи шифрования.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications/push")
@EnableConfigurationProperties(WebPushProperties.class)
@Tag(name = "Web Push", description = "Подписки браузеров на push-уведомления")
@SecurityRequirement(name = "bearer-jwt")
public class PushController {

    private final PushSubscriptionRepository repository;
    private final WebPushProperties properties;
    private final CurrentUser currentUser;

    @GetMapping("/public-key")
    @Operation(summary = "Открытый ключ VAPID для PushManager.subscribe (503, если push выключен)")
    public ResponseEntity<Map<String, String>> publicKey() {
        if (!properties.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Web Push выключен: не заданы ключи VAPID"));
        }
        return ResponseEntity.ok(Map.of("publicKey", properties.vapidPublicKey()));
    }

    @PostMapping("/subscriptions")
    @Operation(summary = "Сохранить подписку этого браузера (PushSubscription.toJSON())")
    public ResponseEntity<Map<String, String>> subscribe(
            @Valid @RequestBody SubscriptionRequest request,
            @RequestHeader(name = "User-Agent", required = false) String userAgent
    ) {
        if (!properties.configured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", "Web Push выключен"));
        }
        if (!allowedEndpoint(request.endpoint())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Адрес подписки не похож на push-сервис браузера"));
        }
        repository.upsert(currentUser.requireId(), request.endpoint(), request.keys().p256dh(), request.keys().auth(),
                userAgent == null ? null : userAgent.substring(0, Math.min(300, userAgent.length())));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "subscribed"));
    }

    @DeleteMapping("/subscriptions")
    @Operation(summary = "Отписать этот браузер")
    public ResponseEntity<Void> unsubscribe(@RequestParam String endpoint) {
        repository.deleteForUser(currentUser.requireId(), endpoint);
        return ResponseEntity.noContent().build();
    }

    /**
     * Адрес присылает браузер, а запрос по нему сделает наш сервер — это SSRF, если пустить любой.
     * Поэтому только https и только хосты известных push-сервисов.
     */
    private boolean allowedEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equals(uri.getScheme()) && properties.allowedHosts().stream()
                    .anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public record SubscriptionRequest(
            @NotBlank @Size(max = 1024) String endpoint,
            @NotNull @Valid Keys keys
    ) {
        public record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {
        }
    }
}
