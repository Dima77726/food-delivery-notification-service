package com.dima.fooddelivery.notification.telegram;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Включает привязку настроек Telegram только вместе с каналом: выключенный канал не требует ни
 * токена, ни имени бота.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelegramProperties.class)
@ConditionalOnProperty(name = "app.notification.telegram.enabled", havingValue = "true")
public class TelegramConfig {
}
