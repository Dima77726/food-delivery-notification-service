package com.dima.fooddelivery.notification.api;

import com.dima.fooddelivery.notification.config.CurrentUser;
import com.dima.fooddelivery.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Уведомления текущего пользователя.
 *
 * <p>Идентификатор берётся из токена, а не из пути: здесь нет сценария «посмотреть чужие
 * уведомления», поэтому и параметра, который можно подделать, быть не должно. Это надёжнее
 * любой проверки прав — защищать нечего.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notification", description = "Уведомления пользователя")
@SecurityRequirement(name = "bearer-jwt")
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    @GetMapping("/me")
    @Operation(summary = "Мои уведомления")
    public List<NotificationResponse> myNotifications() {
        return NotificationResponseMapper.toResponses(
                notificationService.getNotificationsForUser(currentUser.requireId())
        );
    }
}
