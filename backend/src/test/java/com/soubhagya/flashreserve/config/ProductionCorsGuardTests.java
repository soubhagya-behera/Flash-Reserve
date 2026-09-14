package com.soubhagya.flashreserve.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the production CORS fail-fast guard (browser preflight
 * incident). In production a blank allow-list used to start "successfully",
 * answer every preflight without {@code Access-Control-Allow-*} headers and
 * leave the frontend blocked with no backend error. The guard now fails
 * startup instead. Proves:
 *   1. production + blank/whitespace/wildcard allow-list -> fail fast with a
 *      message naming CORS_ALLOWED_ORIGINS
 *   2. production + explicit origins -> configured exactly as before
 *      (credentials, explicit origins only)
 *   3. local development (no production profile) + blank allow-list ->
 *      unchanged: CORS stays off, same as the Vite-proxy setup
 */
class ProductionCorsGuardTests {

	private static MockEnvironment productionEnvironment(String rawOrigins) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("production");
		environment.setProperty(CorsConfig.ALLOWED_ORIGINS_PROPERTY, rawOrigins);
		return environment;
	}

	private static MockHttpServletRequest preflight(String origin, String method) {
		MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/auth/login");
		request.addHeader("Origin", origin);
		request.addHeader("Access-Control-Request-Method", method);
		return request;
	}

	@Test
	void productionFailsFastWhenAllowListIsBlank() {
		CorsConfig config = new CorsConfig(productionEnvironment(""));

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				config::corsConfigurationSource);

		assertTrue(failure.getMessage().contains("CORS_ALLOWED_ORIGINS"),
				"message must name the environment variable: " + failure.getMessage());
		assertTrue(failure.getMessage().contains("production"),
				"message must name the profile: " + failure.getMessage());
	}

	@Test
	void productionFailsFastWhenAllowListIsWhitespaceOnly() {
		CorsConfig config = new CorsConfig(productionEnvironment("   "));

		assertThrows(IllegalStateException.class, config::corsConfigurationSource);
	}

	@Test
	void productionFailsFastWhenAllowListIsWildcard() {
		// The wildcard was never accepted (filtered out by parsing); in
		// production the resulting empty list now fails startup instead of
		// silently disabling CORS.
		CorsConfig config = new CorsConfig(productionEnvironment("*"));

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				config::corsConfigurationSource);

		assertTrue(failure.getMessage().contains("CORS_ALLOWED_ORIGINS"));
	}

	@Test
	void productionSucceedsWithExplicitOriginList() {
		MockEnvironment environment = productionEnvironment(
				"https://flashreserve.vercel.app, https://app.example.com ,");
		CorsConfig config = new CorsConfig(environment);

		CorsConfigurationSource source = config.corsConfigurationSource();

		CorsConfiguration cors = source.getCorsConfiguration(
				preflight("https://flashreserve.vercel.app", "POST"));
		assertNotNull(cors, "registered for /** so preflight lookups resolve");
		assertEquals(List.of("https://flashreserve.vercel.app", "https://app.example.com"),
				cors.getAllowedOrigins());
		assertTrue(Boolean.TRUE.equals(cors.getAllowCredentials()));
		assertEquals(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"), cors.getAllowedMethods());
		assertEquals(Long.valueOf(3600L), cors.getMaxAge());
	}

	@Test
	void productionSucceedsWithEnvironmentStylePlaceholderValue() {
		// Mirrors the real deployment: the classpath property resolves the
		// CORS_ALLOWED_ORIGINS placeholder recursively through the environment.
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("production");
		environment.setProperty("CORS_ALLOWED_ORIGINS", "https://flashreserve.vercel.app");
		environment.setProperty(CorsConfig.ALLOWED_ORIGINS_PROPERTY, "${CORS_ALLOWED_ORIGINS:}");
		CorsConfig config = new CorsConfig(environment);

		CorsConfiguration cors = config.corsConfigurationSource()
				.getCorsConfiguration(preflight("https://flashreserve.vercel.app", "POST"));

		assertNotNull(cors);
		assertEquals(List.of("https://flashreserve.vercel.app"), cors.getAllowedOrigins());
	}

	@Test
	void productionSucceedsWithoutPropertiesFileUsingRawEnvironmentVariable() {
		// Simulates a deployed jar that carries no application.properties at
		// all (the file is git-ignored): the placeholder mapping
		// app.cors.allowed-origins=${CORS_ALLOWED_ORIGINS:} is then undefined
		// and only the raw environment variable can supply the allow-list.
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("production");
		environment.setProperty(CorsConfig.ENVIRONMENT_VARIABLE, "https://flashreserve.vercel.app");
		CorsConfig config = new CorsConfig(environment);

		CorsConfiguration cors = config.corsConfigurationSource()
				.getCorsConfiguration(preflight("https://flashreserve.vercel.app", "POST"));

		assertNotNull(cors, "raw env var fallback must activate CORS without any properties file");
		assertEquals(List.of("https://flashreserve.vercel.app"), cors.getAllowedOrigins());
		assertTrue(Boolean.TRUE.equals(cors.getAllowCredentials()));
	}

	@Test
	void localDevelopmentStillAllowsBlankAllowList() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty(CorsConfig.ALLOWED_ORIGINS_PROPERTY, "");
		CorsConfig config = new CorsConfig(environment);

		CorsConfigurationSource source = config.corsConfigurationSource();

		assertNull(source.getCorsConfiguration(preflight("https://app.example.com", "GET")),
				"local dev must keep the previous behavior: CORS off, empty source");
		assertTrue(source instanceof UrlBasedCorsConfigurationSource);
	}

	@Test
	void localDevelopmentStillFiltersWildcard() {
		MockEnvironment environment = new MockEnvironment();
		environment.setProperty(CorsConfig.ALLOWED_ORIGINS_PROPERTY, "*");
		CorsConfig config = new CorsConfig(environment);

		assertNull(config.corsConfigurationSource().getCorsConfiguration(
				preflight("https://app.example.com", "GET")),
				"wildcard must stay rejected (filtered), never expanded, outside production");
	}
}
