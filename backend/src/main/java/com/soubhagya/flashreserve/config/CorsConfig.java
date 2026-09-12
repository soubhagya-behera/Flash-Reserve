package com.soubhagya.flashreserve.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Production-safe CORS using environment-driven allow-list.
 * <p>
 * Reads {@code CORS_ALLOWED_ORIGINS} (exposed as {@code app.cors.allowed-origins})
 * as a comma-separated list. Wildcard {@code *} is never allowed when
 * credentials are involved. Empty value disables cross-origin access (local
 * dev works via Vite same-origin proxy).
 */
@Configuration
public class CorsConfig {

	@Value("${app.cors.allowed-origins:}")
	private String allowedOrigins;

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		if (allowedOrigins == null || allowedOrigins.isBlank()) {
			return source;
		}
		List<String> origins = Arrays.stream(allowedOrigins.split(","))
				.map(String::trim)
				.filter(s -> !s.isBlank())
				.filter(s -> !"*".equals(s))
				.toList();
		if (origins.isEmpty()) {
			return source;
		}
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(origins);
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin"));
		config.setExposedHeaders(List.of("Retry-After"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		source.registerCorsConfiguration("/**", config);
		return source;
	}
}
