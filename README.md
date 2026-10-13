# food-delivery-notification-service

Сервис платформы доставки еды. Платформа целиком (docker-compose, наблюдаемость, Postman,
общая документация) — в репозитории [food-delivery-platform](https://github.com/Dima77726/food-delivery-platform);
контракты событий, gRPC и REST — в [food-delivery-contracts](https://github.com/Dima77726/food-delivery-contracts).

## Сборка

Контракты лежат в GitHub Packages, а он требует токен даже для чтения. Один раз в
`~/.m2/settings.xml`:

```xml
<servers>
  <server>
    <id>github</id>
    <username>ВАШ_GITHUB_ЛОГИН</username>
    <password>ТОКЕН_С_ПРАВОМ_read:packages</password>
  </server>
</servers>
```

```bash
./mvnw verify
```

Интеграционным тестам нужен Docker (Testcontainers).

## Образ

```bash
docker build --secret id=maven_settings,src=$HOME/.m2/settings.xml -t food-delivery-notification-service .
```

CI публикует образ в `ghcr.io/<владелец>/food-delivery-notification-service` на каждый пуш в `main` и `dev`.

## Доставка по e-mail

По умолчанию уведомления уходят «в лог». С `EMAIL_ENABLED=true` канал EMAIL отправляет настоящие
письма по SMTP; SMS и PUSH по-прежнему остаются в логе — у них нет шлюза.

```
событие заказа → уведомление (PENDING) → задача в RabbitMQ → работник:
    gRPC identity-service: GetUserContact(userId)  →  адрес, имя, «включён»
    SMTP: письмо  →  уведомление SENT
```

- **Адреса в событии нет.** Событие заказа читают многие потребители, а адрес — персональные
  данные, поэтому сервис знает получателя по номеру и спрашивает контакт у identity-service в
  момент отправки. Цена — зависимость от него; её смягчают повторы очереди и предохранитель на
  канале. Копия адресов в этом сервисе была бы независимее, но это второй источник персональных
  данных, который нужно синхронизировать и чистить.
- **Постоянная и временная ошибки.** Получателя нет, он заблокирован, адреса нет —
  `PermanentDeliveryException`: уведомление сразу `FAILED` с причиной, без пяти кругов через очередь
  ожидания. Недоступный SMTP или identity-service — временная ошибка, задача уходит на повтор
  (`NOTIFICATION_MAX_ATTEMPTS`, пауза `NOTIFICATION_RETRY_DELAY`).
- **«Хотя бы один раз».** Отправка идёт внутри транзакции доставки: если SMTP принял письмо, а
  коммит не удался, письмо уйдёт повторно. «Ровно один раз» без распределённой транзакции
  невозможно, и выбор сделан в пользу повтора, а не потери.
- **mTLS.** В docker-compose канал к identity-service идёт по взаимному TLS (профиль `grpc-tls`),
  identity-service пускает сервис по имени `notification-service` из сертификата.
- В лог адрес не пишется, только номер пользователя.

Настройки: `MAIL_HOST`/`MAIL_PORT` (по умолчанию `localhost:1025`, это Mailpit из compose),
`MAIL_USER`/`MAIL_PASSWORD`/`MAIL_AUTH`/`MAIL_STARTTLS` для настоящего SMTP-провайдера,
`EMAIL_FROM`, `IDENTITY_GRPC_ADDRESS`. Письма локально видны в интерфейсе Mailpit:
http://localhost:8025.
