package com.amenbank.banking_webapp.config;

import com.amenbank.banking_webapp.security.JwtAuthFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

/**
 * Optimized Security Configuration with:
 * - Bean Life Cycle management
 * - Enhanced CORS configuration
 * - Proper password encoder singleton
 * - Authentication provider configuration
 * - Rate limiting hints
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // Enables @PreAuthorize on controllers/services
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

        private static final String BEAN_NAME = "SecurityConfig";

        private final JwtAuthFilter jwtAuthFilter;
        private final UserDetailsService userDetailsService;

        @Value("${cors.allowed-origins}")
        private String allowedOrigins;

        // ============================================================
        // Bean Life Cycle - @PostConstruct
        // ============================================================
        @PostConstruct
        public void init() {
                log.info("=== {}: PostConstruct - Security Configuration initialized ===", BEAN_NAME);
                log.info("{}: CORS allowed origins: {}", BEAN_NAME, allowedOrigins);
        }

        // ============================================================
        // Bean Life Cycle - @PreDestroy
        // ============================================================
        @PreDestroy
        public void destroy() {
                log.info("=== {}: PreDestroy - Security resources cleanup ===", BEAN_NAME);
        }

        // ============================================================
        // Security Filter Chain
        // ============================================================
        @Bean
        public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                http
                                // CORS configuration
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                                // CSRF disabled for stateless JWT API
                                .csrf(csrf -> csrf.disable())

                                // Stateless session management
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                                // Authorization rules
                                .authorizeHttpRequests(auth -> auth
                                                // ── Public endpoints (no auth required) ──────
                                                .requestMatchers("/auth/register", "/auth/login",
                                                                "/auth/refresh", "/auth/2fa/verify")
                                                .permitAll()
                                                .requestMatchers("/agencies/**").permitAll()
                                                .requestMatchers("/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**")
                                                .permitAll()
                                                .requestMatchers("/actuator/health").permitAll()

                                                // ── Admin-only endpoints ──────────────────────
                                                .requestMatchers("/admin/**").hasRole("ADMIN")

                                                // ── Agent + Admin endpoints ───────────────────
                                                .requestMatchers("/agent/**").hasAnyRole("AGENT", "ADMIN")
                                                .requestMatchers("/fraud-alerts/**").hasAnyRole("AGENT", "ADMIN")

                                                // ── All other endpoints require authentication ─
                                                .anyRequest().authenticated())

                                // Custom exception handling with JSON responses
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint((request, response, authException) -> {
                                                        log.warn("{}: Authentication failed for {} - {}",
                                                                        BEAN_NAME, request.getRequestURI(),
                                                                        authException.getMessage());

                                                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                                                        response.setContentType("application/json;charset=UTF-8");
                                                        response.getWriter()
                                                                        .write("""
                                                                                        {"timestamp":"%s","status":401,"error":"Unauthorized","message":"Token JWT manquant ou invalide. Veuillez vous connecter via POST /auth/login"}
                                                                                        """
                                                                                        .formatted(java.time.LocalDateTime
                                                                                                        .now()));
                                                })
                                                .accessDeniedHandler((request, response, accessDeniedException) -> {
                                                        log.warn("{}: Access denied for {} - {}",
                                                                        BEAN_NAME, request.getRequestURI(),
                                                                        accessDeniedException.getMessage());

                                                        response.setStatus(HttpStatus.FORBIDDEN.value());
                                                        response.setContentType("application/json;charset=UTF-8");
                                                        response.getWriter()
                                                                        .write("""
                                                                                        {"timestamp":"%s","status":403,"error":"Forbidden","message":"Accès refusé — permissions insuffisantes pour cette ressource"}
                                                                                        """
                                                                                        .formatted(java.time.LocalDateTime
                                                                                                        .now()));
                                                }))

                                // Add JWT filter before username/password filter
                                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                                // Configure authentication provider
                                .authenticationProvider(authenticationProvider());

                return http.build();
        }

        // ============================================================
        // Password Encoder Bean (Singleton)
        // ============================================================
        @Bean
        public PasswordEncoder passwordEncoder() {
                log.debug("{}: Creating BCryptPasswordEncoder bean", BEAN_NAME);
                return new BCryptPasswordEncoder(12); // Stronger work factor
        }

        // ============================================================
        // Authentication Provider
        // ============================================================
        @Bean
        public DaoAuthenticationProvider authenticationProvider() {
                DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
                authProvider.setUserDetailsService(userDetailsService);
                authProvider.setPasswordEncoder(passwordEncoder());
                log.debug("{}: DaoAuthenticationProvider configured", BEAN_NAME);
                return authProvider;
        }

        // ============================================================
        // Authentication Manager Bean
        // ============================================================
        @Bean
        public AuthenticationManager authenticationManager(
                        AuthenticationConfiguration authConfig) throws Exception {
                log.debug("{}: Getting AuthenticationManager from config", BEAN_NAME);
                return authConfig.getAuthenticationManager();
        }

        // ============================================================
        // CORS Configuration - Improved Security
        // ============================================================
        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();

                // Set allowed origins from config
                configuration.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));

                // Allowed HTTP methods
                configuration.setAllowedMethods(Arrays.asList(
                                "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));

                // Allowed headers - specify explicitly for security
                configuration.setAllowedHeaders(Arrays.asList(
                                "Authorization",
                                "Content-Type",
                                "X-Requested-With",
                                "Accept",
                                "X-CSRF-Token"));

                // Exposed headers
                configuration.setExposedHeaders(Arrays.asList(
                                "Authorization",
                                "X-Total-Count",
                                "X-Page-Number"));

                // Allow credentials
                configuration.setAllowCredentials(true);

                // Cache preflight response
                configuration.setMaxAge(3600L);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);

                log.debug("{}: CORS configuration applied", BEAN_NAME);
                return source;
        }
}
