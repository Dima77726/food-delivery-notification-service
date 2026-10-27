package com.dima.fooddelivery.notification.api;

import com.dima.fooddelivery.notification.config.CurrentUser;
import com.dima.fooddelivery.notification.live.NotificationPollResponse;
import com.dima.fooddelivery.notification.live.NotificationPollWaiters;
import com.dima.fooddelivery.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.List;

/**
 * Уведомления текущего пользователя.
 *
 * <p>Идентификатор берётся из токена, а не из пути: здесь нет сценария «посмотреть чужие
 * уведомления», поэтому и параметра, который можно подделать, быть не должно. Это надёжнее
 * любой проверки прав — защищать нечего.
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notification", description = "Уведомления пользователя")
@SecurityRequirement(name = "bearer-jwt")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    private final NotificationPollWaiters pollWaiters;

    @GetMapping("/me")
    @Operation(summary = "Мои уведомления")
    public List<NotificationResponse> myNotifications() {
        return NotificationResponseMapper.toResponses(
                notificationService.getNotificationsForUser(currentUser.requireId())
        );
    }

    /**
     * Long polling: ответ, как только появится уведомление новее {@code after}, или пустой
     * список по таймауту. Потолок ожидания — 30 секунд, меньше таймаута nginx (60 с) с запасом.
     */
    @GetMapping("/me/poll")
    @Operation(summary = "Новые уведомления после курсора: ответ сразу или по появлению (long polling)")
    public DeferredResult<NotificationPollResponse> pollMyNotifications(
            @Min(0) @RequestParam(defaultValue = "0") long after,
            @Min(1) @Max(30) @RequestParam(defaultValue = "25") int timeoutSeconds
    ) {
        return pollWaiters.await(currentUser.requireId(), after, Duration.ofSeconds(timeoutSeconds));
    }
}
