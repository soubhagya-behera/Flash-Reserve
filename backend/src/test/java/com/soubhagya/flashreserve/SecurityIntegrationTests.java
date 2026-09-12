package com.soubhagya.flashreserve;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ThreadLocalRandom;

import org.redisson.api.RedissonClient;

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

	private static final String FUTURE_PROTECTED_PATH = "/api/bookings";

	private static final String FUTURE_ADMIN_PATH = "/api/admin/events";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private RedissonClient redissonClient;

	private static final String ADMIN_CREATE_BODY = """
			{"name":"Sample Event","description":"d","venue":"Hall A","eventDate":"2027-01-01T18:00:00Z","totalSeats":5,"ticketPrice":250.00}""";

	private String registerBody(String email) {
		return """
				{"name":"Test User","email":"%s","password":"password-123"}""".formatted(email);
	}

	private void markVerified(String email) {
		String key = "flashreserve:otp:verified:" + email.toLowerCase();
		redissonClient.getBucket(key).set(email.toLowerCase(), 10, TimeUnit.MINUTES);
	}

	private String randomIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(0, 256) + "." + r.nextInt(0, 256) + "." + r.nextInt(2, 255);
	}

	private RequestPostProcessor fromIp(String ip) {
		return req -> { req.setRemoteAddr(ip); return req; };
	}

	private String registerAndGetToken(String email) throws Exception {
		markVerified(email);
		String ip = randomIp();
		String response = mockMvc.perform(post(REGISTER_URL)
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content(registerBody(email)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(response, "$.accessToken");
	}

	@Test
	void registrationSucceedsAndReturnsSafeAuthResponse() throws Exception {
		String email = "register-ok@gmail.com";
		markVerified(email);
		mockMvc.perform(post(REGISTER_URL)
						.with(fromIp(randomIp()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(registerBody(email)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").isNumber())
				.andExpect(jsonPath("$.user.email").value(email))
				.andExpect(jsonPath("$.user.role").value("USER"))
				.andExpect(jsonPath("$.user.password").doesNotExist())
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	@Test
	void duplicateEmailRegistrationIsRejected() throws Exception {
		String email = "duplicate@gmail.com";
		markVerified(email);
		mockMvc.perform(post(REGISTER_URL)
						.with(fromIp(randomIp()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(registerBody(email)))
				.andExpect(status().isCreated());

		// Second attempt needs a fresh verified marker (first was consumed)
		markVerified(email);
		mockMvc.perform(post(REGISTER_URL)
						.with(fromIp(randomIp()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(registerBody(email)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Email already registered"));
	}

	@Test
	void registrationRejectsInvalidPayloadsWithFieldErrors() throws Exception {
		String body = """
				{"name":"","email":"not-an-email","password":"short"}""";
		mockMvc.perform(post(REGISTER_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.name").exists())
				.andExpect(jsonPath("$.fieldErrors.email").exists())
				.andExpect(jsonPath("$.fieldErrors.password").exists());
	}

	@Test
	void malformedJsonBodyIsRejectedAsBadRequest() throws Exception {
		mockMvc.perform(post(LOGIN_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{not-json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Malformed request body"));
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
		registerAndGetToken("login-ok@gmail.com");

		String body = """
				{"email":"login-ok@gmail.com","password":"password-123"}""";
		mockMvc.perform(post(LOGIN_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.user.email").value("login-ok@gmail.com"));
	}

	@Test
	void loginRejectsWrongPasswordWithGenericError() throws Exception {
		registerAndGetToken("wrong-pass@gmail.com");

		String body = """
				{"email":"wrong-pass@gmail.com","password":"definitely-wrong"}""";
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
	void protectedEndpointsRejectUnauthenticatedRequests() throws Exception {
		mockMvc.perform(get(FUTURE_PROTECTED_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Authentication required"))
				.andExpect(jsonPath("$.status").value(401));
	}

	@Test
	void invalidJwtIsRejectedOnProtectedEndpoint() throws Exception {
		mockMvc.perform(get(FUTURE_PROTECTED_PATH)
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

		mockMvc.perform(get(FUTURE_PROTECTED_PATH)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void validUserTokenPassesSecurityLayerOnPublicEventListing() throws Exception {
		String token = registerAndGetToken("role-user@gmail.com");

		mockMvc.perform(get(FUTURE_PROTECTED_PATH)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/events")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
	}

	@Test
	void userTokenIsForbiddenOnAdminArea() throws Exception {
		String token = registerAndGetToken("admin-area-user@gmail.com");

		mockMvc.perform(get(FUTURE_ADMIN_PATH)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Access denied"))
				.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	void adminTokenPassesAdminAuthorizationRule() throws Exception {
		User admin = userRepository.save(new User("Admin", "admin-role@example.test",
				passwordEncoder.encode("admin-password-123"), UserRole.ADMIN));

		String token = jwtService.generateToken(admin);
		assertThat(jwtService.parseToken(token).role()).isEqualTo(UserRole.ADMIN);

		mockMvc.perform(post(FUTURE_ADMIN_PATH)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(ADMIN_CREATE_BODY))
				.andExpect(status().isCreated());
	}

}
