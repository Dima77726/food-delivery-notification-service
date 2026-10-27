package com.dima.fooddelivery.notification.config;

import com.dima.fooddelivery.notification.web.UserIdMdcFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Сервис — resource server: принимает токены, выпущенные identity-service, и проверяет их сам.
 *
 * <p><b>Почему проверка здесь, а не только в шлюзе.</b> Шлюз — не единственная дорога
 * к сервису: внутри сети до него может достучаться кто угодно, от соседнего контейнера
 * до забытого отладочного скрипта. Модель «шлюз проверил, сервисы верят заголовкам»
 * держится ровно до первой такой дыры. Каждый сервис проверяет подпись сам — это
 * дешевле одного инцидента.
 *
 * <p><b>RS256 и JWKS.</b> Токены подписывает identity-service закрытым ключом, здесь они
 * проверяются открытым из его JWKS. Секрета, которым можно подписать поддельный токен,
 * у сервиса нет вовсе.
 *
 * <p>Издатель проверяется явно. Валидатор по умолчанию смотрит только срок жизни, и токен,
 * подписанный тем же ключом, но выпущенный для другой системы, прошёл бы.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

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
                        // Повторный проход асинхронного запроса (long polling) — продолжение уже
                        // разрешённого: права проверены на первом проходе.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .addFilterAfter(new UserIdMdcFilter(currentUser), BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Проверка подписи по JWKS сервиса пользователей и издателя. JWKS скачивается лениво,
     * при первом токене, и перечитывается на незнакомый {@code kid} — смена ключа
     * не требует перезапуска. Принимается только RS256.
     */
    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwtProperties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));

        return decoder;
    }
}
