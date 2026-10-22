package com.example.autoauction.auth.infrastructure.security;

import com.example.autoauction.auth.domain.port.PasswordChangeRequirementRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserDetailsService userDetailsService;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;
    private final PasswordChangeRequirementRepository passwordChangeRequirementRepository;

    @org.springframework.beans.factory.annotation.Value("${allowed.origins:http://localhost:5173}")
    private String allowedOrigins;

    /**
     * PROD-SEC-01: Content-Security-Policy for every API response. Empty (no header) by default, because the
     * development Swagger UI is an HTML page with scripts; application-prod.properties sets a deny-all policy
     * (the API serves JSON and images only - Swagger is disabled there).
     */
    @org.springframework.beans.factory.annotation.Value("${security.content-security-policy:}")
    private String contentSecurityPolicy;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthFilter,
            UserDetailsService userDetailsService,
            JwtAuthenticationEntryPoint authenticationEntryPoint,
            JwtAccessDeniedHandler accessDeniedHandler,
            PasswordChangeRequirementRepository passwordChangeRequirementRepository) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.userDetailsService = userDetailsService;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.passwordChangeRequirementRepository = passwordChangeRequirementRepository;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .headers(headers -> {
                    if (!contentSecurityPolicy.isBlank()) {
                        headers.contentSecurityPolicy(csp -> csp.policyDirectives(contentSecurityPolicy));
                    }
                })
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Публичные — без авторизации
                        .requestMatchers(
                                new AntPathRequestMatcher("/api/health"),
                                new AntPathRequestMatcher("/api/auth/**"),
                                new AntPathRequestMatcher("/api/public/**"),
                                new AntPathRequestMatcher("/api/v1/public/**"),
                                new AntPathRequestMatcher("/swagger-ui/**"),
                                new AntPathRequestMatcher("/swagger-ui.html"),
                                new AntPathRequestMatcher("/api-docs/**"),
                                new AntPathRequestMatcher("/ws-auction/**"),
                                new AntPathRequestMatcher("/ws/**"),
                                new AntPathRequestMatcher("/uploads/**")
                        ).permitAll()

                        // Админ (legacy + v1)
                        .requestMatchers(new AntPathRequestMatcher("/api/admin/**")).hasRole("ADMIN")
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/admin/**")).hasRole("ADMIN")

                        // Диагностик: работа (только DIAGNOSTIC/ADMIN)
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/diagnostic/work/**")).hasAnyRole("DIAGNOSTIC", "ADMIN")

                        // Диагностик: заявки — любой авторизованный (роль проверяется в @PreAuthorize)
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/diagnostic/**")).authenticated()

                        // Диагностик legacy
                        .requestMatchers(new AntPathRequestMatcher("/api/diagnostic/**")).hasAnyRole("DIAGNOSTIC", "ADMIN")

                        // Аукционы (legacy + v1)
                        .requestMatchers(new AntPathRequestMatcher("/api/auctions/**")).authenticated()
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/auctions/**")).authenticated()

                        // Депозиты (legacy + v1)
                        .requestMatchers(new AntPathRequestMatcher("/api/deposits/**")).authenticated()
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/deposits/**")).authenticated()

                        // Уведомления, медиа
                        .requestMatchers(new AntPathRequestMatcher("/api/notifications/**")).authenticated()
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/notifications/**")).authenticated()
                        .requestMatchers(new AntPathRequestMatcher("/api/media/**")).authenticated()

                        // Пользователи
                        .requestMatchers(new AntPathRequestMatcher("/api/users/**")).authenticated()

                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                // AUTH-01: a temporary password set by an administrator must be replaced first
                .addFilterAfter(new PasswordChangeRequiredFilter(passwordChangeRequirementRepository),
                        JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        configuration.setAllowedOrigins(origins);

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        source.registerCorsConfiguration("/ws-auction/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}