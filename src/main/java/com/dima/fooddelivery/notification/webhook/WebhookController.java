package com.dima.fooddelivery.notification.webhook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Вебхуки ресторана: подписки на события заказов и журнал доставок. Управляет владелец ресторана —
 * из кабинета или по токену, полученному за ключ API.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/restaurants/{restaurantId}/webhooks")
@Tag(name = "Webhooks", description = "Исходящие вебхуки: подписки и журнал доставок")
@SecurityRequirement(name = "bearer-jwt")
public class WebhookController {

    private final WebhookService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Подписаться на события заказов (секрет подписи показывается один раз)")
    public SubscriptionResponse create(@PathVariable long restaurantId, @Valid @RequestBody CreateRequest request) {
        WebhookService.Created created = service.create(restaurantId, request.url(), request.eventTypes(), request.description());
        return SubscriptionResponse.of(created.subscription(), created.secret());
    }

    @GetMapping
    @Operation(summary = "Подписки ресторана")
    public List<SubscriptionResponse> list(@PathVariable long restaurantId) {
        return service.list(restaurantId).stream().map(s -> SubscriptionResponse.of(s, null)).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Удалить подписку вместе с журналом")
    public void delete(@PathVariable long restaurantId, @PathVariable long id) {
        service.delete(restaurantId, id);
    }

    @PostMapping("/{id}/rotate-secret")
    @Operation(summary = "Новый секрет подписи; старый действует ещё 24 часа")
    public SubscriptionResponse rotate(@PathVariable long restaurantId, @PathVariable long id) {
        WebhookService.Created rotated = service.rotateSecret(restaurantId, id);
        return SubscriptionResponse.of(rotated.subscription(), rotated.secret());
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Включить подписку (в том числе выключенную автоматически)")
    public SubscriptionResponse enable(@PathVariable long restaurantId, @PathVariable long id) {
        return SubscriptionResponse.of(service.enable(restaurantId, id), null);
    }

    @PostMapping("/{id}/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Отправить пробное событие webhook.test")
    public Map<String, Long> test(@PathVariable long restaurantId, @PathVariable long id) {
        return Map.of("deliveryId", service.sendTest(restaurantId, id));
    }

    @GetMapping("/{id}/deliveries")
    @Operation(summary = "Журнал доставок подписки, новые первыми")
    public List<DeliveryResponse> deliveries(
            @PathVariable long restaurantId,
            @PathVariable long id,
            @Min(1) @Max(200) @RequestParam(defaultValue = "50") int limit
    ) {
        return service.deliveries(restaurantId, id, limit).stream().map(DeliveryResponse::of).toList();
    }

    @PostMapping("/{id}/deliveries/{deliveryId}/redeliver")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Доставить заново (тот же webhook-id — получатель распознает повтор)")
    public void redeliver(@PathVariable long restaurantId, @PathVariable long id, @PathVariable long deliveryId) {
        service.redeliver(restaurantId, id, deliveryId);
    }

    public record CreateRequest(
            @NotBlank(message = "Укажите адрес") @Size(max = 2048) String url,
            @NotEmpty(message = "Выберите хотя бы один тип события или *") List<@NotBlank String> eventTypes,
            @Size(max = 200) String description
    ) {
    }

    /** @param secret секрет подписи: только в ответе на создание и ротацию */
    public record SubscriptionResponse(
            long id,
            long restaurantId,
            String url,
            String description,
            List<String> eventTypes,
            boolean active,
            int consecutiveFailures,
            String disabledReason,
            OffsetDateTime createdAt,
            String secret
    ) {
        static SubscriptionResponse of(WebhookRepository.Subscription s, String secret) {
            return new SubscriptionResponse(s.id(), s.restaurantId(), s.url(), s.description(), s.eventTypes(),
                    s.active(), s.consecutiveFailures(), s.disabledReason(), s.createdAt(), secret);
        }
    }

    public record DeliveryResponse(
            long id,
            UUID webhookId,
            String eventType,
            String status,
            int attempts,
            Integer lastStatusCode,
            String lastError,
            OffsetDateTime createdAt,
            OffsetDateTime lastAttemptAt,
            OffsetDateTime nextAttemptAt,
            OffsetDateTime deliveredAt,
            String payload
    ) {
        static DeliveryResponse of(WebhookRepository.Delivery d) {
            return new DeliveryResponse(d.id(), d.eventId(), d.eventType(), d.status(), d.attempts(), d.lastStatusCode(),
                    d.lastError(), d.createdAt(), d.lastAttemptAt(), "PENDING".equals(d.status()) ? d.nextAttemptAt() : null,
                    d.deliveredAt(), d.payload());
        }
    }
}
