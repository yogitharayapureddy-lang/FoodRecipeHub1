
package com.example.food_recipe_backend.config;

import com.example.food_recipe_backend.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.frontend.url:https://food-recipe-hub1.vercel.app}")
    private String frontendUrl;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        // Allow local development and deployed frontend
        config.setAllowedOrigins(List.of(
            "http://localhost:5173",
            "http://localhost:5174",
            frontendUrl.replaceAll("/+$", "")
        ));

        config.setAllowedMethods(List.of(
            "GET",
            "POST",
            "PUT",
            "PATCH",
            "DELETE",
            "OPTIONS"
        ));

        config.setAllowedHeaders(List.of(
            "Authorization",
            "Content-Type"
        ));

        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);

        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtFilter
    ) throws Exception {

        http
            .csrf(csrf -> csrf.disable())

            .cors(cors -> {})

            .sessionManagement(session ->
                session.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS
                )
            )

            .authorizeHttpRequests(auth -> auth

                // Allow error dispatches
                .dispatcherTypeMatchers(
                    DispatcherType.ERROR
                ).permitAll()

                // Allow CORS preflight requests
                .requestMatchers(
                    HttpMethod.OPTIONS,
                    "/**"
                ).permitAll()

                // Public registration and login
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/users/register",
                    "/api/users/login"
                ).permitAll()

                // Public donation endpoints
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/donations/create-order",
                    "/api/donations/verify"
                ).permitAll()

                // Public recipe browsing
                .requestMatchers(
                    HttpMethod.GET,
                    "/api/recipes",
                    "/api/recipes/**"
                ).permitAll()

                // All other endpoints require JWT
                .anyRequest().authenticated()
            )

            .addFilterBefore(
                jwtFilter,
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }
}
