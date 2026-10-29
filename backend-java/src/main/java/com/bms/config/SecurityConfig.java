package com.bms.config;

import com.bms.repo.LoginActivityRepository;
import com.bms.repo.UserRepository;
import com.bms.security.JsonResponses;
import com.bms.security.RateLimitFilter;
import com.bms.security.ServiceTokenFilter;
import com.bms.security.UserRefreshFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public JsonResponses jsonResponses(ObjectMapper mapper) {
        return new JsonResponses(mapper);
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(BmsProperties props) {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        repo.setCookieCustomizer(c -> c.sameSite("Strict"));
        return repo;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, BmsProperties props, UserRepository users,
                                           LoginActivityRepository loginActivity, JsonResponses json,
                                           CookieCsrfTokenRepository csrfRepo,
                                           SecurityContextRepository contextRepository) throws Exception {
        http
            .cors(c -> c.configurationSource(corsSource(props)))
            .csrf(c -> c
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                // token-authenticated service endpoints; no browser cookies are involved
                .ignoringRequestMatchers("/internal/**", "/actuator/**"))
            .securityContext(sc -> sc.securityContextRepository(contextRepository))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .exceptionHandling(e -> e
                .authenticationEntryPoint(json.entryPoint())
                .accessDeniedHandler(json.accessDenied()))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/auth/csrf", "/api/v1/auth/session",
                        "/api/v1/auth/providers").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register",
                        "/api/v1/auth/google").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/actuator/shutdown", "/internal/**").hasRole("SERVICE")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/staff/**").hasAnyRole("ADMIN", "EMPLOYEE")
                .requestMatchers("/api/v1/**").hasAnyRole("ADMIN", "EMPLOYEE", "CUSTOMER")
                .anyRequest().denyAll())
            .addFilterBefore(new RateLimitFilter(props.security().rateLimit(), json), SecurityContextHolderFilter.class)
            .addFilterAfter(new ServiceTokenFilter(props.security().internalApiToken()), SecurityContextHolderFilter.class)
            .addFilterBefore(new UserRefreshFilter(users, loginActivity, json), AuthorizationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsSource(BmsProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.security().corsAllowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cfg.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
