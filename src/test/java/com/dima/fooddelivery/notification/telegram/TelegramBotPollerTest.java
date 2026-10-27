package com.dima.fooddelivery.notification.telegram;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Один круг опроса бота — без потока и без сети.
 *
 * <p>Позиция опроса сохраняется после каждого обновления: при падении посреди пачки повторится
 * не больше одного, и уже использованные коды привязки не получат ответ «ссылка недействительна»
 * на то, что давно сработало.
 */
class TelegramBotPollerTest {

    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramBotHandler handler = mock(TelegramBotHandler.class);
    private final TelegramLinkRepository repository = mock(TelegramLinkRepository.class);
    private final TelegramProperties properties = new TelegramProperties(
            true, "token", "bot", "http://localhost", true,
            Duration.ofSeconds(25), Duration.ofMinutes(15), Duration.ofSeconds(3), Duration.ofSeconds(10),
            "polling", null, null
    );

    private final TelegramBotPoller poller = new TelegramBotPoller(client, handler, repository, properties);

    @Test
    void shouldContinueFromSavedOffsetAndAdvanceAfterEachUpdate() {
        when(repository.loadNextOffset()).thenReturn(10L);
        TelegramClient.Update first = new TelegramClient.Update(10, 1, "private", "/start a");
        TelegramClient.Update second = new TelegramClient.Update(12, 1, "private", "/stop");
        when(client.getUpdates(10L, 25)).thenReturn(List.of(first, second));

        poller.pollOnce();

        var order = inOrder(handler, repository);
        order.verify(handler).handle(first);
        order.verify(repository).saveNextOffset(11L);
        order.verify(handler).handle(second);
        // Позиция — следующий после обработанного: Telegram считает всё меньшее прочитанным.
        order.verify(repository).saveNextOffset(13L);
    }

    @Test
    void shouldNotTouchOffsetWhenThereAreNoUpdates() {
        when(repository.loadNextOffset()).thenReturn(10L);
        when(client.getUpdates(10L, 25)).thenReturn(List.of());

        poller.pollOnce();

        verify(repository, never()).saveNextOffset(anyLong());
    }

    /** Не удалось ответить человеку (заблокировал бота сразу после /start) — не повод застрять на обновлении. */
    @Test
    void shouldMoveOnWhenReplyCannotBeDelivered() {
        when(repository.loadNextOffset()).thenReturn(0L);
        TelegramClient.Update update = new TelegramClient.Update(5, 1, "private", "/start a");
        when(client.getUpdates(0L, 25)).thenReturn(List.of(update));
        doThrow(new TelegramClient.TelegramApiException(403, "blocked")).when(handler).handle(update);

        poller.pollOnce();

        verify(repository).saveNextOffset(6L);
    }

    /** Сбой самой обработки (например, базы) — обновление не подтверждается и придёт снова. */
    @Test
    void shouldKeepUpdateForRetryWhenHandlingFails() {
        when(repository.loadNextOffset()).thenReturn(0L);
        TelegramClient.Update update = new TelegramClient.Update(5, 1, "private", "/start a");
        when(client.getUpdates(0L, 25)).thenReturn(List.of(update));
        doThrow(new IllegalStateException("база недоступна")).when(handler).handle(update);

        assertThrows(IllegalStateException.class, poller::pollOnce);

        verify(repository, never()).saveNextOffset(anyLong());
    }
}
