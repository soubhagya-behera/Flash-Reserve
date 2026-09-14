package com.soubhagya.flashreserve.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Production-safe CORS using environment-driven allow-list.
 * <p>
 * Reads {@code CORS_ALLOWED_ORIGINS} as a comma-separated list. Wildcard
 * {@code *} is never allowed when credentials are involved. Empty value
 * disables cross-origin access (local dev works via Vite same-origin proxy).
 * <p>
 * <b>Resolution order</b> (all standard Spring Boot mechanisms):
 * <ol>
 *   <li>the classpath property {@code app.cors.allowed-origins} (packaged
 *       example copy / explicit property; resolves placeholders recursively,</li>
 *   <li>the canonical relaxed binding {@code cors.allowed-origins}
 *       ({@link CorsProperties}) - the documented environment-variable contract
 *       for {@code CORS_ALLOWED_ORIGINS}, independent of any properties file,</li>
 *   <li>the raw {@code CORS_ALLOWED_ORIGINS} environment variable read
 *       directly from the {@code systemEnvironment} property source.</li>
 * </ol>
 * Paths 2 and 3 exist because the deployed artifact does not necessarily carry
 * any {@code application.properties} (that file is git-ignored), and because
 * exact-name lookups fail when the container's variable NAME carries invisible
 * anomalies (e.g. a trailing space) even though the value is correct.
 * <p>
 * <b>Production fail-fast:</b> when the {@code production} profile is active,
 * an empty allow-list is a deployment defect, not a configuration choice.
 * A blank list used to start "successfully" and then silently answered every
 * browser preflight without {@code Access-Control-Allow-*} headers, which
 * blocked the frontend with no error in the backend logs. Before failing,
 * the environment is scanned (names only, never values) and the result is
 * logged, so a Render-side name/scope problem is visible in the deploy log
 * instead of being a mystery.
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

	private final CorsProperties corsProperties;

	private final Environment environment;

	public CorsConfig(CorsProperties corsProperties, Environment environment) {
		this.corsProperties = corsProperties;
		this.environment = environment;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource() {
		// Standard mechanisms only: packaged property first, then Boot's
		// canonical relaxed binding of the environment variable (works with
		// no properties file at all), then a direct exact-name environment
		// lookup as the final safety net.
		String raw = firstNonBlank(environment.getProperty(ALLOWED_ORIGINS_PROPERTY),
				corsProperties.allowedOrigins(),
				environment.getProperty(ENVIRONMENT_VARIABLE));
		List<String> origins = parseAllowedOrigins(raw);
		if (origins.isEmpty() && environment.acceptsProfiles(Profiles.of("production"))) {
			logProductionEnvironmentDiagnostics();
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

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return null;
	}

	/**
	 * Logs, at WARN, why the allow-list resolved to empty - using environment
	 * variable NAMES only, never values. This turns the previously silent
	 * "dashboard shows the variable but the app cannot resolve it" failure
	 * into an actionable deploy-log line: it distinguishes a blank/wildcard
	 * value, an anomalous variable name (invisible whitespace/case variants),
	 * and a variable that is genuinely absent from the container.
	 */
	private void logProductionEnvironmentDiagnostics() {
		try {
			if (!(environment instanceof ConfigurableEnvironment configurable)) {
				return;
			}
			Map<String, Object> systemEnvironment = configurable.getSystemEnvironment();
			if (systemEnvironment == null) {
				return;
			}
			if (systemEnvironment.containsKey(ENVIRONMENT_VARIABLE)) {
				log.warn("CORS fail-fast diagnostics: env var {} IS visible in the container but parses to no "
						+ "origin (blank, whitespace or wildcard-only value).", ENVIRONMENT_VARIABLE);
				return;
			}
			List<String> lookalikeNames = systemEnvironment.keySet().stream()
					.filter(key -> !ENVIRONMENT_VARIABLE.equals(key))
					.filter(key -> ENVIRONMENT_VARIABLE.equals(key.trim().toUpperCase()))
					.toList();
			if (!lookalikeNames.isEmpty()) {
				log.warn("CORS fail-fast diagnostics: env var {} is NOT visible under its exact name. Keys with "
						+ "the same normalized name (names only, never values): {}. Check the deployment "
						+ "environment variable NAME for invisible whitespace or case variants and redeploy.",
						ENVIRONMENT_VARIABLE, lookalikeNames);
				return;
			}
			log.warn("CORS fail-fast diagnostics: env var {} is not present in the container environment at all. "
					+ "Verify it is set on the deployment environment this instance belongs to.",
					ENVIRONMENT_VARIABLE);
		}
		catch (RuntimeException ex) {
			log.debug("CORS environment diagnostics unavailable", ex);
		}
	}
}

