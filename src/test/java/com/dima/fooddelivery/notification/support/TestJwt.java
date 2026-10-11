package com.dima.fooddelivery.notification.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Выпускает токены так же, как identity-service: RS256, {@code kid}, издатель, {@code sub} —
 * числовой идентификатор, {@code roles} и {@code email}.
 *
 * <p>Сервиса пользователей в тестах нет, и ходить за его JWKS некуда. Поэтому пара ключей
 * генерируется здесь, один раз на прогон, а тестовая конфигурация подменяет декодер на тот,
 * что знает открытую половину. Проверяется ровно то, что принадлежит этому сервису: что он
 * понимает токен правильной формы и отвергает неправильный. Совпадение формы с настоящими
 * токенами проверяют тесты identity-service — JWKS там проверяется по-настоящему.
 */
public class TestJwt {

    public static final String ISSUER = "food-delivery-platform";

    private static final KeyPair KEYS = generate();

    /** Идентификаторы далеко от тех, что выдаст база: в тестах, где пользователей нет вовсе. */
    private static final AtomicLong USER_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L + 1_000_000_000L);

    private final NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(
            new RSAKey.Builder((RSAPublicKey) KEYS.getPublic())
                    .privateKey((RSAPrivateKey) KEYS.getPrivate())
                    .keyID("test-key")
                    .build()
    )));

    /** Декодер, которым тестовая конфигурация подменяет JWKS-декодер сервиса. */
    public static JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));

        return decoder;
    }

    public long newUserId() {
        return USER_IDS.incrementAndGet();
    }

    public String tokenFor(long userId, String... roles) {
        return tokenWithIssuer(userId, ISSUER, roles);
    }

    /** Токен от чужого издателя — для проверки, что такие отвергаются. */
    public String tokenWithIssuer(long userId, String issuer, String... roles) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(10)))
                .subject(String.valueOf(userId))
                .claim("roles", List.of(roles))
                .claim("email", "user-" + userId + "@example.test")
                .build();

        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build();

        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Новый пользователь с ролями — токен для него. */
    public String newUserToken(String... roles) {
        return tokenFor(newUserId(), roles);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
