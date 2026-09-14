package com.soubhagya.flashreserve;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.security.JwtService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production-profile CORS preflight behavior (browser preflight incident).
 *
 * The deployed frontend could not call the backend because every browser
 * preflight (OPTIONS /api/auth/login) fell through to the dispatcher and was
 * answered without Access-Control-Allow-* headers. These tests drive the exact
 * production resolution path - the classpath property
 * app.cors.allowed-origins placeholder resolving through
 * the environment variable - and prove:
 *   1. a preflight to /api/** with the allowed production origin succeeds and
 *      includes the correct CORS headers
 *   2. a disallowed origin is rejected on preflight
 *   3. the real POST /api/auth/login flow works behind the preflight and
 *      authorization/security behavior is unchanged
 */
class ProductionCorsIntegrationTests {

	private static final String PRODUCTION_ORIGIN = "https://flashreserve.vercel.app";

	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("production")
	@Transactional
	@TestPropertySource(properties = {
			// Simulated deployment environment variable: it feeds the classpath
			// placeholder app.cors.allowed-origins exactly like a real Render env.
			"CORS_ALLOWED_ORIGINS=https://flashreserve.vercel.app",
			"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
			"jwt.expiration-ms=900000"
	})
	static class ProductionPreflightWithEnvOriginTests {

		@Autowired MockMvc mockMvc;

		@Autowired UserRepository userRepository;

		@Autowired PasswordEncoder passwordEncoder;

		@Autowired JwtService jwtService;

		@Test
		void preflightToLoginWithProductionOriginSucceeds() throws Exception {
			mockMvc.perform(options("/api/auth/login")
							.header(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN)
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PRODUCTION_ORIGIN))
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "content-type"))
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"));
		}

		@Test
		void preflightFromDisallowedOriginIsRejected() throws Exception {
			mockMvc.perform(options("/api/auth/login")
							.header(HttpHeaders.ORIGIN, "https://evil.example.com")
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type"))
					.andExpect(status().isForbidden());
		}

		@Test
		void preflightToProtectedApiWithAuthorizationHeaderSucceeds() throws Exception {
			// CORS runs before authorization, so protected endpoints still
			// answer their preflight normally.
			mockMvc.perform(options("/api/bookings")
							.header(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN)
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
							.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PRODUCTION_ORIGIN));
		}

		@Test
		void actualLoginPostStillWorksWithCorsHeaders() throws Exception {
			// The request behind the preflight: login stays public (permitAll)
			// and a validation failure returns the existing 400 - now with the
			// CORS header the browser requires to read the response.
			mockMvc.perform(post("/api/auth/login")
							.header(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PRODUCTION_ORIGIN));
		}

		@Test
		void protectedEndpointStaysUnauthorizedWithCorsHeaders() throws Exception {
			// Security unchanged: no token -> 401, but the browser still gets
			// the CORS header so the frontend can read the error body.
			mockMvc.perform(get("/api/bookings")
							.header(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN))
					.andExpect(status().isUnauthorized())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PRODUCTION_ORIGIN));
		}

		@Test
		void authenticatedUserRequestFromProductionOriginIsAuthorized() throws Exception {
			User user = userRepository.save(new User("Prod CORS User", "prod-cors@example.test",
					passwordEncoder.encode("prod-cors-password-123"), UserRole.USER));

			mockMvc.perform(get("/api/bookings")
							.header(HttpHeaders.ORIGIN, PRODUCTION_ORIGIN)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(user)))
					.andExpect(status().isOk())
					.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PRODUCTION_ORIGIN));
		}
	}
}
