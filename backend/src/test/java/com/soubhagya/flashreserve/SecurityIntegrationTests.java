package com.soubhagya.flashreserve;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.security.JwtService;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000"
})
class SecurityIntegrationTests {

	private static final String TEST_SECRET = "test-secret-that-is-definitely-longer-than-32-bytes!!";

	private static final String REGISTER_URL = "/api/auth/register";

	private static final String LOGIN_URL = "/api/auth/login";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private String registerBody(String email) {
		return """
				{"name":"Test User","email":"%s","password":"password-123"}""".formatted(email);
	}

	private String registerAndGetToken(String email) throws Exception {
		String response = mockMvc.perform(post(REGISTER_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(registerBody(email)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(response, "$.accessToken");
	}

	@Test
	void registrationSucceedsAndReturnsSafeAuthResponse() throws Exception {
		mockMvc.perform(post(REGISTER_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(registerBody("register-ok@example.test")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").isNumber())
				.andExpect(jsonPath("$.user.email").value("register-ok@example.test"))
				.andExpect(jsonPath("$.user.role").value("USER"))
				.andExpect(jsonPath("$.user.password").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	@Test
	void duplicateEmailRegistrationIsRejected() throws Exception {
		mockMvc.perform(post(REGISTER_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(registerBody("duplicate@example.test")))
				.andExpect(status().isCreated());

		mockMvc.perform(post(REGISTER_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(registerBody("duplicate@example.test")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Email already registered"));
	}

	@Test
	void registrationRejectsInvalidPayloads() throws Exception {
		String body = """
				{"name":"","email":"not-an-email","password":"short"}""";
		mockMvc.perform(post(REGISTER_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.name").exists())
				.andExpect(jsonPath("$.errors.email").exists())
				.andExpect(jsonPath("$.errors.password").exists());
	}

	@Test
	void passwordIsStoredHashedAndNeverInPlaintext() {
		String rawPassword = "password-123";

		userRepository.save(new User("Hash Check", "hash-check@example.test",
				passwordEncoder.encode(rawPassword), UserRole.USER));
		userRepository.flush();

		User stored = userRepository.findByEmail("hash-check@example.test").orElseThrow();
		assertThat(stored.getPassword()).isNotEqualTo(rawPassword);
		assertThat(stored.getPassword()).startsWith("$2");
		assertThat(passwordEncoder.matches(rawPassword, stored.getPassword())).isTrue();
	}

	@Test
	void loginSucceedsWithValidCredentialsAndReturnsJwt() throws Exception {
		registerAndGetToken("login-ok@example.test");

		String body = """
				{"email":"login-ok@example.test","password":"password-123"}""";
		mockMvc.perform(post(LOGIN_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.user.email").value("login-ok@example.test"));
	}

	@Test
	void loginRejectsWrongPasswordWithGenericError() throws Exception {
		registerAndGetToken("wrong-pass@example.test");

		String body = """
				{"email":"wrong-pass@example.test","password":"definitely-wrong"}""";
		mockMvc.perform(post(LOGIN_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid email or password"));
	}

	@Test
	void loginRejectsUnknownEmailWithoutRevealingWhichFieldFailed() throws Exception {
		String body = """
				{"email":"ghost@example.test","password":"whatever-123"}""";
		mockMvc.perform(post(LOGIN_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Invalid email or password"));
	}

	@Test
	void protectedEndpointRejectsUnauthenticatedRequests() throws Exception {
		mockMvc.perform(get("/api/demo/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Authentication required"));
	}

	@Test
	void invalidJwtIsRejectedOnProtectedEndpoint() throws Exception {
		mockMvc.perform(get("/api/demo/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void expiredJwtIsRejectedOnProtectedEndpoint() throws Exception {
		SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
		String expiredToken = Jwts.builder()
				.subject(UUID.randomUUID().toString())
				.claim("email", "expired@example.test")
				.claim("role", UserRole.USER.name())
				.issuedAt(Date.from(Instant.now().minusSeconds(7200)))
				.expiration(Date.from(Instant.now().minusSeconds(3600)))
				.signWith(key)
				.compact();

		mockMvc.perform(get("/api/demo/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void userRoleCanAccessUserEndpointsButNotAdminEndpoints() throws Exception {
		String token = registerAndGetToken("role-user@example.test");

		mockMvc.perform(get("/api/demo/me")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("role-user@example.test"))
				.andExpect(jsonPath("$.role").value("USER"));

		mockMvc.perform(get("/api/admin/ping")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Access denied"));
	}

	@Test
	void adminRoleCanAccessAdminEndpoints() throws Exception {
		User admin = userRepository.save(new User("Admin", "admin-role@example.test",
				passwordEncoder.encode("admin-password-123"), UserRole.ADMIN));

		String token = jwtService.generateToken(admin);

		mockMvc.perform(get("/api/admin/ping")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("admin-ok"));

		assertThat(jwtService.parseToken(token).role()).isEqualTo(UserRole.ADMIN);
	}

}
