package com.magizhchi.cloud.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    /**
     * Browser callers (Meet's Pawn Shop rooms) need CORS; the Android app and
     * the sync agents are unaffected either way. Origins are an explicit
     * allowlist rather than "*", because these responses carry shop data and
     * the Authorization header must be allowed through.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${pawnbroking.cors.origins:}") String origins) {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> allowed = Arrays.stream(origins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        cfg.setAllowedOrigins(allowed);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // X-Shop-Id is how a suite (Magizhchi ID) caller names the shop it wants.
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Shop-Id"));
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http,
                                     ApiKeyFilter apiKeyFilter,
                                     JwtFilter jwtFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(org.springframework.security.config.Customizer.withDefaults())
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Our custom filters perform authentication (API key for /v1/sync, JWT for the rest).
            // Spring Security's authorization layer simply permits all traffic; controllers can
            // still inspect the SecurityContext for role-based checks if needed.
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
