package com.dima.fooddelivery.notification.config;

import com.dima.fooddelivery.notification.web.UserIdMdcFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Сервис — resource server: принимает токены, выпущенные монолитом, и проверяет их сам.
 *
 * <p><b>Почему проверка здесь, а не только в шлюзе.</b> Шлюз — не единственная дорога
 * к сервису: внутри сети до него может достучаться кто угодно, от соседнего контейнера
 * до забытого отладочного скрипта. Модель «шлюз проверил, сервисы верят заголовкам»
 * держится ровно до первой такой дыры. Каждый сервис проверяет подпись сам — это
 * дешевле одного инцидента.
 *
 * <p><b>Почему HS256 — временно.</b> Симметричный ключ означает, что любой, кто умеет
 * проверять токен, умеет его и выпускать: секрет один. Пока сервисов два и оба свои, это
 * терпимо. Следующий шаг — вынести выпуск токенов в identity-service с RS256: он подписывает
 * закрытым ключом, остальные проверяют открытым через JWKS и подделать токен не могут.
 *
 * <p>Издатель проверяется явно. Валидатор по умолчанию смотрит только срок жизни, и токен,
 * подписанный тем же ключом, но выпущенный для другой системы, прошёл бы.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    /** HS256 требует ключ не короче 256 бит. */
    static final int MIN_SECRET_LENGTH_BYTES = 32;

    private static final String[] PUBLIC_PATHS = {
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final JwtProperties jwtProperties;
    private final CurrentUser currentUser;

    /**
     * Служебные эндпоинты открыты: actuator слушает отдельный порт, который наружу
     * не публикуется. Защита здесь сетевая — у Prometheus нет способа обновлять JWT.
     */
    @Bean
    @Order(1)
    SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterAfter(new UserIdMdcFilter(currentUser), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));

        return decoder;
    }

    /**
     * Проверка ключа на месте, а не на {@code ApplicationReadyEvent}: пустой секрет ломает
     * {@code SecretKeySpec} раньше, с сообщением «Empty key», по которому не догадаться,
     * что дело в переменной окружения.
     */
    private SecretKeySpec secretKey() {
        String secret = jwtProperties.secret();

        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET должен быть задан и быть не короче " + MIN_SECRET_LENGTH_BYTES
                            + " байт: этого требует HS256. Значение обязано совпадать с тем, "
                            + "которым монолит подписывает токены"
            );
        }

        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
