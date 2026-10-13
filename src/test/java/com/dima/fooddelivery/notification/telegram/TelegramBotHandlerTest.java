package com.dima.fooddelivery.notification.telegram;

import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Что бот отвечает на сообщения. Привязки и Bot API подменены.
 */
class TelegramBotHandlerTest {

    private final TelegramLinkService links = mock(TelegramLinkService.class);
    private final TelegramClient client = mock(TelegramClient.class);
    private final TelegramBotHandler handler = new TelegramBotHandler(links, client);

    @Test
    void shouldLinkChatOnStartWithValidCode() {
        when(links.completeLink("code-1", 555L)).thenReturn(true);

        handler.handle(update(555L, "private", "/start code-1"));

        verify(links).completeLink("code-1", 555L);
        verify(client).sendMessage(org.mockito.ArgumentMatchers.eq(555L), org.mockito.ArgumentMatchers.contains("Готово"));
    }

    /** Использованная или просроченная ссылка: человек должен узнать об этом, а не гадать, работает ли бот. */
    @Test
    void shouldExplainInvalidCode() {
        when(links.completeLink("old", 555L)).thenReturn(false);

        handler.handle(update(555L, "private", "/start old"));

        verify(client).sendMessage(org.mockito.ArgumentMatchers.eq(555L), org.mockito.ArgumentMatchers.contains("недействительна"));
    }

    @Test
    void shouldShowHelpOnBareStartAndUnknownText() {
        handler.handle(update(555L, "private", "/start"));
        handler.handle(update(555L, "private", "привет"));

        verify(links, never()).completeLink(anyString(), anyLong());
        verify(client, org.mockito.Mockito.times(2)).sendMessage(555L, TelegramBotHandler.HELP);
    }

    @Test
    void shouldUnlinkOnStop() {
        when(links.unlinkChat(555L)).thenReturn(true);

        handler.handle(update(555L, "private", "/stop"));

        verify(links).unlinkChat(555L);
        verify(client).sendMessage(org.mockito.ArgumentMatchers.eq(555L), org.mockito.ArgumentMatchers.contains("отключены"));
    }

    @Test
    void shouldTellWhenThereWasNothingToUnlink() {
        when(links.unlinkChat(555L)).thenReturn(false);

        handler.handle(update(555L, "private", "/stop"));

        verify(client).sendMessage(org.mockito.ArgumentMatchers.eq(555L), org.mockito.ArgumentMatchers.contains("не подключён"));
    }

    /**
     * В группе /start мог бы отправить любой участник, и уведомления о заказе читали бы все:
     * привязки принимаются только из личной переписки, а бот в группах молчит.
     */
    @Test
    void shouldIgnoreGroupsAndChannels() {
        handler.handle(update(-100L, "supergroup", "/start code-1"));
        handler.handle(update(-200L, "channel", "/stop"));

        verify(links, never()).completeLink(anyString(), anyLong());
        verify(links, never()).unlinkChat(anyLong());
        verify(client, never()).sendMessage(anyLong(), anyString());
    }

    @Test
    void shouldStaySilentOnEmptyMessage() {
        handler.handle(update(555L, "private", ""));

        verify(client, never()).sendMessage(anyLong(), anyString());
    }

    private static TelegramClient.Update update(long chatId, String chatType, String text) {
        return new TelegramClient.Update(1L, chatId, chatType, text);
    }
}
