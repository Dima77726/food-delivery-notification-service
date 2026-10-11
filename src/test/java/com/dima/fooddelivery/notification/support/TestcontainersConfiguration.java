package com.dima.fooddelivery.notification.support;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
