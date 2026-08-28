package com.soubhagya.flashreserve;

import java.util.concurrent.ThreadLocalRandom;

import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.repository.UserRepository;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the distributed per-client-IP rate limits on the public
 * authentication endpoints against the real Redis instance. A tiny policy
 * (3 attempts / 10 min) makes exhaustion deterministic; the 10-minute refill
 * window guarantees no permit is restored while a test is running.
 *
 * Each test uses a fresh random TEST-NET-1 client IP so its Redis bucket is
 * isolated from other tests and from previous runs (buckets outlive the
 * rolled-back test transactions).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"auth.login.capacity=3",
		"auth.login.refill-period=10m",
		"auth.registration.capacity=3",
		"auth.registration.refill-period=10m"
})
class AuthRateLimitIntegrationTests {

	private static final String LOGIN_URL = "/api/auth/login";

	private static final String REGISTER_URL = "/api/auth/register";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private String randomClientIp() {
		return "192.0.2." + ThreadLocalRandom.current().nextInt(2, 255);
	}

	private RequestPostProcessor fromClientIp(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

	private int attemptLogin(String ip, String email, String password) throws Exception {
		String body = "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password);
		MvcResult result = mockMvc.perform(post(LOGIN_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body)
						.with(fromClientIp(ip)))
				.andReturn();
		return result.getResponse().getStatus();
	}

	private int attemptRegistration(String ip, String email) throws Exception {
		String body = "{\"name\":\"Rate Limited User\",\"email\":\"%s\",\"password\":\"password-123\"}"
				.formatted(email);
		MvcResult result = mockMvc.perform(post(REGISTER_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body)
						.with(fromClientIp(ip)))
				.andReturn();
		return result.getResponse().getStatus();
	}

	@Test
	void excessiveLoginsReturn429WithRetryAfterAndSafeBody() throws Exception {
		String ip = randomClientIp();

		for (int i = 0; i < 3; i++) {
			assertThat(attemptLogin(ip, "rl-login@example.test", "wrong-password"))
					.as("pre-limit login attempts must reach credential checking")
					.isEqualTo(HttpStatus.UNAUTHORIZED.value());
		}

		mockMvc.perform(post(LOGIN_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"rl-login@example.test\",\"password\":\"wrong-password\"}")
						.with(fromClientIp(ip)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "600"))
				.andExpect(jsonPath("$.message")
						.value("Too many authentication attempts. Please try again shortly."))
				.andExpect(jsonPath("$.status").value(429));
	}

	@Test
	void excessiveRegistrationsReturn429WithRetryAfter() throws Exception {
		String ip = randomClientIp();
		String ipTag = String.valueOf(ip.hashCode());

		for (int i = 0; i < 3; i++) {
			assertThat(attemptRegistration(ip, "rl-reg-" + ipTag + "-" + i + "@example.test"))
					.as("pre-limit registration attempts must succeed")
					.isEqualTo(HttpStatus.CREATED.value());
		}

		mockMvc.perform(post(REGISTER_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Rate Limited User\","
								+ "\"email\":\"rl-reg-overflow" + ipTag + "@example.test\","
								+ "\"password\":\"password-123\"}")
						.with(fromClientIp(ip)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "600"));
	}

	@Test
	void exhaustedLoginLimitDoesNotBlockRegistrationForTheSameClient() throws Exception {
		String ip = randomClientIp();
		String ipTag = String.valueOf(ip.hashCode());

		for (int i = 0; i < 3; i++) {
			attemptLogin(ip, "rl-scopes@example.test", "wrong-password");
		}
		assertThat(attemptLogin(ip, "rl-scopes@example.test", "wrong-password"))
				.as("login bucket is exhausted")
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());

		assertThat(attemptRegistration(ip, "rl-scopes-" + ipTag + "@example.test"))
				.as("registration has its own bucket and still works")
				.isEqualTo(HttpStatus.CREATED.value());
	}

	@Test
	void rateLimitedLoginsNeverReachCredentialVerification() throws Exception {
		User user = userRepository.save(new User("Valid User", "rl-valid@example.test",
				passwordEncoder.encode("correct-password-123"), UserRole.USER));
		String ip = randomClientIp();

		for (int i = 0; i < 3; i++) {
			attemptLogin(ip, user.getEmail(), "wrong-password");
		}

		assertThat(attemptLogin(ip, user.getEmail(), "correct-password-123"))
				.as("even VALID credentials must be rejected once the limit is exhausted")
				.isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
	}

}
