package com.dima.fooddelivery.notification.support;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Настоящие PostgreSQL, Kafka и RabbitMQ для тестов сервиса.
 *
 * <p>Образ брокера — Confluent, а не apache/kafka из compose. Причина та же, что в монолите:
 * обёртка образа apache/kafka не уживается со способом, которым Testcontainers выставляет
 * advertised-адреса. Подробности — в TestcontainersConfiguration монолита.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String[] VAPID = com.dima.fooddelivery.notification.push.WebPushCrypto.newVapidKeys();

    /** {@code @Primary}: настоящий gRPC-клиент в контексте есть, но его канал ленивый и никуда не ходит. */
    @Bean
    @Primary
    TestRestaurantAccess testRestaurantAccess() {
        return new TestRestaurantAccess();
    }

    /**
     * Ключ шифрования секретов вебхуков: без него сервис не стартует, а в тестах он нужен любой.
     * Короткая лестница повторов — чтобы цепочка попыток укладывалась в секунды; localhost
     * разрешён — получатели в тестах живут на нём.
     */
    @Bean
    DynamicPropertyRegistrar webhookTestProperties() {
        return registry -> {
            registry.add("app.webhooks.secret-key", () -> "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=");
            registry.add("app.webhooks.retry-delays", () -> "PT0.3S,PT0.6S");
            registry.add("app.webhooks.allowed-private-hosts", () -> "localhost,127.0.0.1");
            registry.add("app.webhooks.disable-after-failures", () -> "2");
            // Пара VAPID на прогон и push-сервис на localhost (в бою — только известные push-сервисы).
            registry.add("app.notification.push.vapid-public-key", () -> VAPID[0]);
            registry.add("app.notification.push.vapid-private-key", () -> VAPID[1]);
        };
    }

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16");
    }

    @Bean
    @ServiceConnection
    ConfluentKafkaContainer kafkaContainer() {
        return new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");
    }

    /**
     * Брокер очередей для рассылки. Образ с плагином управления — тот же, что в compose:
     * тестам интерфейс не нужен, но держать два разных образа одной версии незачем.
     */
    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer("rabbitmq:4.1.4-management");
    }

    @Bean
    TestJwt testJwt() {
        return new TestJwt();
    }

    /**
     * Декодер с открытым ключом {@link TestJwt} вместо JWKS сервиса пользователей — его в тестах
     * нет. {@code @Primary}: настоящий декодер в контексте тоже есть, но JWKS он скачивает лениво
     * и до первого токена никуда не ходит.
     */
    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return TestJwt.decoder();
    }

    /**
     * Основной топик в бою создаёт продюсер — сервис заказов, — а здесь его нет. Тест
     * играет его роль, в том числе в этом: с запретом на автосоздание топиков потребитель
     * без этого бина ждал бы топик, которого никто не создаст.
     */
    @Bean
    NewTopic orderEventsTopic() {
        return TopicBuilder.name(OrderEventTopics.ORDER_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
