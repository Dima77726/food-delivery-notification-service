package com.dima.fooddelivery.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Сервис уведомлений.
 *
 * <p>{@code @EnableScheduling} нужен страховочному опросу зависших уведомлений; саму рассылку
 * ведут очереди RabbitMQ.
 * Без аннотации {@code @Scheduled} молча не запускается, и уведомления копились бы
 * в статусе PENDING без единой ошибки в логе.
 */
@EnableScheduling
@SpringBootApplication
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
