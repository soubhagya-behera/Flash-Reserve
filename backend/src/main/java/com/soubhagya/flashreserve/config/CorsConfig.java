package com.soubhagya.flashreserve.config;

import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
 * <p>
 * <b>Production fail-fast:</b> when the {@code production} profile is active,
 * an empty allow-list is a deployment defect, not a configuration choice.
 * A blank list used to start "successfully" and then silently answered every
 * browser preflight without {@code Access-Control-Allow-*} headers (the
 * preflight fell through to the dispatcher and surfaced as 404/empty), which
 * blocked the frontend with no error in the backend logs. Like the production
 * JWT binding, the application now refuses to start in that state.
 * <p>
 * <b>Resolution order:</b> the classpath property {@code app.cors.allowed-origins}
 * is resolved first (kept for backwards compatibility with the git-ignored
 * local file), then the raw {@code CORS_ALLOWED_ORIGINS} environment variable
 * is consulted directly. The fallback matters because the deployed artifact
 * does not necessarily carry any {@code application.properties} (that file is
 * git-ignored), so the placeholder mapping {@code app.cors.allowed-origins=
 * ${CORS_ALLOWED_ORIGINS:}} can be absent in production while the environment
 * variable itself is still set - the exact silent failure that produced empty
 * preflight responses without CORS headers on Render.
 */
@Configuration
public class CorsConfig {

	private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

	/** Property that mirrors the CORS_ALLOWED_ORIGINS environment variable. */
	static final String ALLOWED_ORIGINS_PROPERTY = "app.cors.allowed-origins";

	/**
	 * Raw environment variable read directly as a fallback. The deployed
	 * artifact may not contain any {@code application.properties}, in which
	 * case {@link #ALLOWED_ORIGINS_PROPERTY} is undefined and only this
	 * variable can supply the allow-list.
	 */
	static final String ENVIRONMENT_VARIABLE = "CORS_ALLOWED_ORIGINS";

	private final Environment environment;

	public CorsConfig(Environment environment) {
		this.environment = environment;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		// Property first (resolves placeholders recursively, so the classpath
		// value ${CORS_ALLOWED_ORIGINS:} still resolves from the real
		// environment variable exactly like the previous @Value injection),
		// then the raw environment variable directly. This covers deployments
		// whose jar carries no application.properties at all.
		String raw = firstNonBlank(environment.getProperty(ALLOWED_ORIGINS_PROPERTY),
				environment.getProperty(ENVIRONMENT_VARIABLE));
		List<String> origins = parseAllowedOrigins(raw);
		if (origins.isEmpty() && environment.acceptsProfiles(Profiles.of("production"))) {
			throw new IllegalStateException(
					"CORS allow-list is empty while the production profile is active. "
							+ "Set the CORS_ALLOWED_ORIGINS environment variable to a comma-separated "
							+ "list of explicit origins (for example https://flashreserve.vercel.app). "
							+ "Wildcard * is never allowed; without this variable the browser preflight "
							+ "requests cannot be answered and the frontend is blocked.");
		}
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		if (origins.isEmpty()) {
			// Local development: CORS stays off; the Vite dev proxy is same-origin.
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
		log.info("CORS allow-list active with {} explicit origin(s)", origins.size());
		return source;
	}

	private static List<String> parseAllowedOrigins(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		return Arrays.stream(raw.split(","))
				.map(String::trim)
				.filter(s -> !s.isBlank())
				.filter(s -> !"*".equals(s))
				.toList();
	}

	private static String firstNonBlank(String primary, String fallback) {
		if (primary != null && !primary.isBlank()) {
			return primary;
		}
		return fallback;
	}
}

