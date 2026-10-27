package com.dima.fooddelivery.notification.webhook;

import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Защита от SSRF (server-side request forgery).
 *
 * <p>Адрес вебхука задаёт партнёр, а запрос по нему делает наш сервер — изнутри нашей сети.
 * Без проверки партнёр подписал бы вебхук на {@code http://postgres:5432}, {@code http://169.254.169.254}
 * (метаданные облака с ключами доступа) или {@code http://localhost:8081/actuator} и получил бы
 * сервер-прокси во внутреннюю сеть. Поэтому:
 * <ul>
 *     <li>только {@code https} — секрет подписи и данные заказов не идут открытым текстом;</li>
 *     <li>все адреса, в которые разрешается имя, должны быть публичными: не loopback, не частные
 *     сети (10/8, 172.16/12, 192.168/16), не link-local (169.254/16 — метаданные облака), не CGNAT
 *     (100.64/10), не IPv6 ULA (fc00::/7);</li>
 *     <li>проверка повторяется перед каждой отправкой: имя, публичное при подписке, потом может
 *     указать внутрь (DNS rebinding);</li>
 *     <li>перенаправления не выполняются — иначе публичный адрес ответил бы 302 на внутренний.</li>
 * </ul>
 *
 * <p>Остаётся узкое окно: между проверкой и соединением имя может разрешиться иначе. Полное
 * решение — соединяться ровно с проверенным IP (свой резолвер HTTP-клиента или исходящий прокси
 * вроде smokescreen); здесь оно не окупается и описано в учебнике.
 *
 * <p>Исключения — {@code app.webhooks.allowed-private-hosts}: приёмник-демонстрация в compose и
 * localhost в тестах. Им разрешены и внутренние адреса, и http.
 */
@Component
public class WebhookUrlGuard {

    private final Set<String> allowedPrivateHosts;

    public WebhookUrlGuard(WebhookProperties properties) {
        this.allowedPrivateHosts = properties.allowedPrivateHosts().stream()
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** @throws InvalidWebhookUrlException с понятной причиной */
    public URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException exception) {
            throw new InvalidWebhookUrlException("Адрес не разбирается как URL");
        }

        String host = uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
        if (host == null || uri.getScheme() == null) {
            throw new InvalidWebhookUrlException("Нужен полный адрес вида https://example.com/hooks");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidWebhookUrlException("Логин и пароль в адресе не допускаются");
        }

        if (allowedPrivateHosts.contains(host)) {
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new InvalidWebhookUrlException("Только http и https");
            }
            return uri;
        }

        if (!"https".equals(uri.getScheme())) {
            throw new InvalidWebhookUrlException("Только https: секрет подписи и данные заказов не должны идти открытым текстом");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new InvalidWebhookUrlException("Имя " + host + " не разрешается в адрес");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new InvalidWebhookUrlException("Адрес " + host + " ведёт во внутреннюю сеть (" + address.getHostAddress() + ")");
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet6Address) {
            // fc00::/7 — уникальные локальные адреса IPv6, аналог частных сетей IPv4.
            return (bytes[0] & 0xFE) != 0xFC;
        }
        // 100.64.0.0/10 — CGNAT; 0.0.0.0/8 — «эта сеть».
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        return !(first == 100 && second >= 64 && second <= 127) && first != 0;
    }

    public static class InvalidWebhookUrlException extends RuntimeException {
        public InvalidWebhookUrlException(String message) {
            super(message);
        }
    }
}
