package com.soubhagya.flashreserve;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS allow-list tests (blocker 1).
 * Verifies production-safe CORS with explicit allow-list, wildcard rejection,
 * and that security remains unchanged.
 */
class CorsIntegrationTests {

	@SpringBootTest
	@AutoConfigureMockMvc
	@TestPropertySource(properties = {
			"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
			"jwt.expiration-ms=900000",
			"app.cors.allowed-origins=https://app.example.com,https://admin.example.com"
	})
	static class AllowedOriginTests {
		@Autowired MockMvc mockMvc;

		@Test
		void allowedOriginGetsCorsHeaders() throws Exception {
			mockMvc.perform(get("/api/events")
							.header(HttpHeaders.ORIGIN, "https://app.example.com"))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.example.com"));
		}

		@Test
		void secondAllowedOriginGetsCorsHeaders() throws Exception {
			mockMvc.perform(get("/api/events")
							.header(HttpHeaders.ORIGIN, "https://admin.example.com"))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://admin.example.com"));
		}

		@Test
		void preflightWithAllowedOriginSucceeds() throws Exception {
			mockMvc.perform(options("/api/events")
							.header(HttpHeaders.ORIGIN, "https://app.example.com")
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.example.com"));
		}

		@Test
		void securityStillEnforcedWithCorsEnabled() throws Exception {
			mockMvc.perform(get("/api/bookings")
							.header(HttpHeaders.ORIGIN, "https://app.example.com"))
					.andExpect(status().isUnauthorized());
		}
	}

	@SpringBootTest
	@AutoConfigureMockMvc
	@TestPropertySource(properties = {
			"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
			"jwt.expiration-ms=900000",
			"app.cors.allowed-origins=https://app.example.com"
	})
	static class DisallowedOriginTests {
		@Autowired MockMvc mockMvc;

		@Test
		void disallowedOriginGetsNoCorsHeader() throws Exception {
			mockMvc.perform(get("/api/events")
							.header(HttpHeaders.ORIGIN, "https://evil.example.com"))
					.andExpect(status().isForbidden());
		}

		@Test
		void wildcardOriginNeverAllowed() throws Exception {
			mockMvc.perform(get("/api/events")
							.header(HttpHeaders.ORIGIN, "*"))
					.andExpect(status().isForbidden());
		}
	}

	@SpringBootTest
	@AutoConfigureMockMvc
	@TestPropertySource(properties = {
			"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
			"jwt.expiration-ms=900000",
			"app.cors.allowed-origins=*"
	})
	static class WildcardRejectedTests {
		@Autowired MockMvc mockMvc;

		@Test
		void wildcardConfigResultsInNoAllowedOrigin() throws Exception {
			mockMvc.perform(get("/api/events")
							.header(HttpHeaders.ORIGIN, "https://app.example.com"))
					.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
		}
	}

	@SpringBootTest
	@AutoConfigureMockMvc
	@TestPropertySource(properties = {
			"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
			"jwt.expiration-ms=900000"
	})
	static class NoCorsConfiguredTests {
		@Autowired MockMvc mockMvc;

		@Test
		void noCorsConfigDoesNotExposeAllowOrigin() throws Exception {
			mockMvc.perform(get("/api/events")
							.header(HttpHeaders.ORIGIN, "https://app.example.com"))
					.andExpect(status().isOk())
					.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
		}

		@Test
		void sameOriginRequestStillWorksWithoutCors() throws Exception {
			mockMvc.perform(get("/api/events"))
					.andExpect(status().isOk());
		}
	}
}
