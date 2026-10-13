package com.dima.fooddelivery.notification.grpc;

/**
 * Контакт получателя в том виде, в каком он нужен отправке: адрес, имя для обращения и признак
 * «включён». Свой тип, а не сгенерированный {@code UserContact}: остальной код не должен зависеть
 * от protobuf.
 *
 * @param fullName может быть пустым
 * @param enabled  {@code false} — пользователь заблокирован
 */
public record UserContactInfo(Long userId, String email, String fullName, boolean enabled) {
}
