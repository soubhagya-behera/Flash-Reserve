package com.soubhagya.flashreserve;

import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.security.JwtService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Production Swagger/OpenAPI hardening (security finding WEB-01).
 *
 * The {@code production} profile disables both Springdoc features, so the
 * API contract cannot be discovered by anonymous users. These tests prove:
 *   1. /v3/api-docs (and its swagger-config) answer 404 in production
 *   2. Swagger UI (and its /swagger-ui.html alias) answer 404 in production
 *   3. the security rules are otherwise unchanged (anonymous -> 401)
 *   4. admin endpoints remain ADMIN-only
 *   5. no API functionality is lost in production apart from the docs
 *
 * Surefire supplies the jwt.secret system property for the whole test run,
 * which (per Spring Boot property-source order) satisfies the production
 * profile's environment-only JWT binding without changing its semantics.
 * Test users are created through the repository directly so no
 * rate-limited /api/auth endpoints are involved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("production")
class ProductionSwaggerDisabledTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	@Test
	void openApiJsonIsUnavailableInProduction() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
	}

	@Test
	void swaggerConfigIsUnavailableInProduction() throws Exception {
		mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isNotFound());
	}

	@Test
	void swaggerUiIsUnavailableInProduction() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
	}

	@Test
	void swaggerUiAliasIsUnavailableInProduction() throws Exception {
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
	}

	@Test
	void securityRulesRemainEnforcedInProduction() throws Exception {
		mockMvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/admin/events")).andExpect(status().isUnauthorized());
	}

	@Test
	void adminEndpointsRemainAdminOnlyInProduction() throws Exception {
		String token = createUserToken(UserRole.USER, "prod-user@example.test");

		mockMvc.perform(get("/api/admin/events")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());
	}

	@Test
	void userApiRemainsAccessibleInProduction() throws Exception {
		String token = createUserToken(UserRole.USER, "prod-user-api@example.test");

		mockMvc.perform(get("/api/events")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/bookings")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
	}

	private String createUserToken(UserRole role, String email) {
		User user = userRepository.save(new User("Prod Test User", email,
				passwordEncoder.encode("prod-test-password-123"), role));
		return jwtService.generateToken(user);
	}

}
