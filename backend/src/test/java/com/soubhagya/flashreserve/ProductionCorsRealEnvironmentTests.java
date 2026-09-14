package com.soubhagya.flashreserve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The EXACT production resolution path, not a mock of it.
 *
 * <p>The surefire JVM inherits a REAL {@code CORS_ALLOWED_ORIGINS} OS
 * environment variable (exported exactly like the Render container does), and
 * a production-profile application context must resolve it through the same
 * chain the deployed jar uses: {@code System.getenv()} -&gt;
 * {@code SystemEnvironmentPropertySource} -&gt; Boot configuration data /
 * canonical relaxed binding -&gt; {@link com.soubhagya.flashreserve.config.CorsConfig}.
 *
 * <p>Deliberately NO CORS-related {@code @TestPropertySource}: that would mock
 * the wrong abstraction (inlined test properties, not the OS environment).
 *
 * <p>Enabled only when the variable is genuinely present in the runner
 * environment; machines and CI runs without it skip instead of failing.
 */
class ProductionCorsRealEnvironmentTests {

	private static final String PRODUCTION_ORIGIN = "https://flashreserve.vercel.app";

	// Enabled on the nested class itself: surefire executes the nested
	// @SpringBootTest class directly under -Dtest=*Cors*Tests, so a condition
	// on the outer holder class would never be evaluated.
	@EnabledIfEnvironmentVariable(named = "CORS_ALLOWED_ORIGINS",
			matches = "https://flashreserve\\.vercel\\.app")
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("production")
	@Transactional
	@TestPropertySource(properties = {
			// Only the JWT is supplied inline; the CORS allow-list MUST come
			// from the real environment variable for every test below.
			"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
			"jwt.expiration-ms=900000"
	})
	static class RealEnvironmentProductionPreflightTests {

		@Autowired MockMvc mockMvc;

		@Autowired CorsConfigurationSource corsConfigurationSource;

		@Test
		void productionStartupSucceedsAndResolvesRealEnvironmentVariable() {
			// The @SpringBootTest context above would have fail-fasted at
			// CorsConfig if the real environment variable were not resolved,
			// so a running context already proves startup does not fail.
			org.springframework.mock.web.MockHttpServletRequest request =
					new org.springframework.mock.web.MockHttpServletRequest("OPTIONS", "/api/auth/login");
			request.addHeader(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN);
			request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
			request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type");

			CorsConfiguration cors = corsConfigurationSource.getCorsConfiguration(request);

			assertNotNull(cors, "the real OS environment variable must resolve to an active allow-list");
			assertTrue(cors.getAllowedOrigins().contains(PRODUCTION_ORIGIN),
					"resolved origins must contain the production origin: " + cors.getAllowedOrigins());
			assertTrue(Boolean.TRUE.equals(cors.getAllowCredentials()));
		}

		@Test
		void preflightToLoginReceivesProductionCorsHeaders() throws Exception {
			mockMvc.perform(options("/api/auth/login")
							.header(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN)
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PRODUCTION_ORIGIN))
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "content-type"));
		}

		@Test
		void disallowedOriginStillRejectedUnderRealEnvironmentVariable() throws Exception {
			mockMvc.perform(options("/api/auth/login")
							.header(HttpHeaders.ORIGIN, "https://evil.example.com")
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
					.andExpect(status().isForbidden());
		}

		@Test
		void securityStillEnforcedBehindRealEnvironmentVariableCors() throws Exception {
			// Authorization unchanged: no token still yields 401, but the
			// preflight-resolved origin header is present for the browser.
			mockMvc.perform(get("/api/bookings")
							.header(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN))
					.andExpect(status().isUnauthorized())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PRODUCTION_ORIGIN));
		}
	}
}