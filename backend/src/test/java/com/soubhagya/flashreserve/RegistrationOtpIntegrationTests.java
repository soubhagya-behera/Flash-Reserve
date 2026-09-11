package com.soubhagya.flashreserve;

import java.util.concurrent.ThreadLocalRandom;

import com.soubhagya.flashreserve.repository.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import org.redisson.api.RedissonClient;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.soubhagya.flashreserve.service.EmailService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"registration.otp.ttl=2s",
		"registration.otp.max-attempts=3",
		"registration.otp.resend-cooldown=1s",
		"registration.otp.max-sends-per-hour=10",
		"auth.registration.capacity=100",
		"auth.registration.refill-period=1m",
		"auth.login.capacity=100"
})
class RegistrationOtpIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RedissonClient redissonClient;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@MockitoBean
	private EmailService emailService;

	private String capturedOtp;

	@BeforeEach
	void captureOtp() {
		capturedOtp = null;
		doAnswer(inv -> {
			capturedOtp = inv.getArgument(1);
			return null;
		}).when(emailService).sendOtpEmail(anyString(), anyString());
	}

	private String randomIp() {
		return "192.0.2." + ThreadLocalRandom.current().nextInt(10, 250);
	}

	private RequestPostProcessor fromIp(String ip) {
		return req -> { req.setRemoteAddr(ip); return req; };
	}

	private void clearOtpState(String email) {
		String lower = email.toLowerCase();
		redissonClient.getBucket("flashreserve:otp:code:" + lower).delete();
		redissonClient.getBucket("flashreserve:otp:verified:" + lower).delete();
		redissonClient.getAtomicLong("flashreserve:otp:sendcount:" + lower).delete();
	}

	@Test
	void validGmailOtpRequestSucceeds() throws Exception {
		String email = "valid-otp@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		assertThat(capturedOtp).matches("^[0-9]{6}$");
	}

	@Test
	void invalidEmailRejected() throws Exception {
		String ip = randomIp();
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"bad@yahoo.com\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Only Gmail addresses (@gmail.com) are allowed"));
	}

	@Test
	void otpGenerationHashedNotPlaintext() throws Exception {
		String email = "hash-check-otp@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		Object state = redissonClient.getBucket("flashreserve:otp:code:" + email).get();
		String stored = state.toString();
		assertThat(stored).doesNotContain(capturedOtp);
	}

	@Test
	void otpExpirationRejectsAfterTtl() throws Exception {
		String email = "expire-otp@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		String code = capturedOtp;
		Thread.sleep(2500);
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void wrongOtpRejected() throws Exception {
		String email = "wrong-otp@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"000000\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Invalid verification code"));
	}

	@Test
	void maxAttemptsBlocks() throws Exception {
		String email = "max-attempts@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(post("/api/auth/register/verify-otp")
							.with(fromIp(ip))
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"email\":\"" + email + "\",\"code\":\"111111\"}"))
					.andExpect(status().isBadRequest());
		}
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"111111\"}"))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void successfulVerificationAllowsRegistration() throws Exception {
		String email = "success-verify@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		userRepository.findByEmail(email).ifPresent(u -> userRepository.delete(u));
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		String code = capturedOtp;
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/register")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\",\"password\":\"password-123\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.user.email").value(email));
	}

	@Test
	void otpCannotBeReused() throws Exception {
		String email = "reuse-otp@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		String code = capturedOtp;
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void resendInvalidatesPreviousOtp() throws Exception {
		String email = "resend-invalidate@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		String first = capturedOtp;
		Thread.sleep(1200);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		String second = capturedOtp;
		assertThat(first).isNotEqualTo(second);
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"" + first + "\"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"" + second + "\"}"))
				.andExpect(status().isOk());
	}

	@Test
	void resendCooldownEnforced() throws Exception {
		String email = "cooldown@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isTooManyRequests());
	}

	@Test
	void registrationRejectedWithoutVerification() throws Exception {
		String email = "no-verify@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		mockMvc.perform(post("/api/auth/register")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\",\"password\":\"password-123\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Email not verified. Please verify your email with the OTP first"));
	}

	@Test
	void registrationSucceedsAfterVerification() throws Exception {
		String email = "final-reg@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		userRepository.findByEmail(email).ifPresent(u -> userRepository.delete(u));
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Final User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		String code = capturedOtp;
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/register")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Final User\",\"email\":\"" + email + "\",\"password\":\"password-123\"}"))
				.andExpect(status().isCreated());
		assertThat(userRepository.findByEmail(email)).isPresent();
	}

	@Test
	void existingRegisteredEmailHandling() throws Exception {
		String email = "existing@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		userRepository.findByEmail(email).ifPresent(u -> userRepository.delete(u));
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		String code = capturedOtp;
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/register")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\",\"password\":\"password-123\"}"))
				.andExpect(status().isCreated());
		Thread.sleep(1200);
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Test User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Email already registered"));
	}

	@Test
	void passwordBcryptAndLoginStillWorks() throws Exception {
		String email = "bcrypt-login@gmail.com";
		String ip = randomIp();
		clearOtpState(email);
		userRepository.findByEmail(email).ifPresent(u -> userRepository.delete(u));
		mockMvc.perform(post("/api/auth/register/send-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Bcrypt User\",\"email\":\"" + email + "\"}"))
				.andExpect(status().isAccepted());
		String code = capturedOtp;
		mockMvc.perform(post("/api/auth/register/verify-otp")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/register")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Bcrypt User\",\"email\":\"" + email + "\",\"password\":\"password-123\"}"))
				.andExpect(status().isCreated());
		var user = userRepository.findByEmail(email).orElseThrow();
		assertThat(user.getPassword()).startsWith("$2");
		assertThat(passwordEncoder.matches("password-123", user.getPassword())).isTrue();
		mockMvc.perform(post("/api/auth/login")
						.with(fromIp(ip))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"" + email + "\",\"password\":\"password-123\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty());
	}
}
