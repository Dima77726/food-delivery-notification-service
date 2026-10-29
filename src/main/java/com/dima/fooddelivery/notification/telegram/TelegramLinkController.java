package com.dima.fooddelivery.notification.telegram;

import com.dima.fooddelivery.notification.config.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * Подключение Telegram пользователем. Каждый управляет только своей привязкой: идентификатор
 * берётся из токена, а не из пути, поэтому чужую привязку не посмотреть и не снять.
 *
 * <p>Ручек нет, пока канал выключен ({@code app.notification.telegram.enabled=false}).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications/telegram")
@Tag(name = "Telegram", description = "Подключение уведомлений в Telegram")
@SecurityRequirement(name = "bearer-jwt")
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class TelegramLinkController {

    private final TelegramLinkService links;
    private final CurrentUser currentUser;

    @PostMapping("/link")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Получить ссылку для подключения Telegram",
            description = "Откройте ссылку в Telegram и нажмите «Запустить» у бота. Ссылка одноразовая; "
                    + "новый запрос отменяет прежнюю.")
    public LinkResponse link() {
        TelegramLinkService.LinkOffer offer = links.issueLinkCode(currentUser.requireId());

        return new LinkResponse(offer.deepLink(), offer.expiresAt());
    }

    @GetMapping
    @Operation(summary = "Подключён ли Telegram")
    public StatusResponse status() {
        Long userId = currentUser.requireId();

        return links.linkedAt(userId)
                .map(linkedAt -> new StatusResponse(true, linkedAt, null))
                .orElse(new StatusResponse(false, null, links.unlinkReason(userId).orElse(null)));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отключить Telegram")
    public void unlink() {
        links.unlinkUser(currentUser.requireId());
    }

    public record LinkResponse(String deepLink, OffsetDateTime expiresAt) {
    }

    /**
     * @param unlinkReason почему привязки нет, если её сняли не вы: {@code BOT_BLOCKED} — бот заблокирован,
     *                     {@code CHAT_NOT_FOUND} — чат удалён; {@code null} — не привязывали или отвязали сами
     */
    public record StatusResponse(boolean linked, OffsetDateTime linkedAt, String unlinkReason) {
    }
}
