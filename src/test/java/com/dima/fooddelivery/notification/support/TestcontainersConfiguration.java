package com.dima.fooddelivery.notification.support;

import com.dima.fooddelivery.contracts.order.OrderEventTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Настоящие PostgreSQL и Kafka для тестов сервиса.
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
