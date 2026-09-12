package com.soubhagya.flashreserve;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
class ForgotPasswordIntegrationTests {

	@Autowired MockMvc mockMvc;
	@Autowired RedissonClient redisson;
	@Autowired UserRepository userRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@MockitoBean EmailService emailService;

	private String capturedOtp;
	private String randomIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(0, 256) + "." + r.nextInt(0, 256) + "." + r.nextInt(2, 255);
	}
	private RequestPostProcessor fromIp(String ip){ return req->{req.setRemoteAddr(ip); return req;};}

	@BeforeEach void capture(){
		capturedOtp=null;
		doAnswer(inv->{ capturedOtp=inv.getArgument(1); return null;}).when(emailService).sendPasswordResetEmail(anyString(), anyString());
		doAnswer(inv->{ capturedOtp=inv.getArgument(1); return null;}).when(emailService).sendOtpEmail(anyString(), anyString());
	}

	private void clear(String email){
		String l=email.toLowerCase();
		redisson.getBucket("flashreserve:recovery:code:"+l).delete();
		redisson.getBucket("flashreserve:recovery:sendcount:"+l).delete();
		// tokens are UUID keys, can't clear by email; rely on TTL, but clear any leftover known tokens not possible
	}

	private String ensureUser(String email){
		userRepository.findByEmail(email).ifPresent(u-> userRepository.delete(u));
		// create via direct repository to avoid OTP flow
		var u = new com.soubhagya.flashreserve.entity.User("Test User", email, passwordEncoder.encode("old-password-123"), com.soubhagya.flashreserve.entity.enums.UserRole.USER);
		userRepository.save(u);
		clear(email);
		return email;
	}

	@Test void forgotValidGmailSucceedsGeneric() throws Exception{
		String email=ensureUser("fp-valid"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value("If an account exists for this email, a verification code has been sent."));
		assertThat(capturedOtp).matches("^[0-9]{6}$");
	}

	@Test void genericDoesNotRevealNonExistent() throws Exception{
		String email="nonexist-"+UUID.randomUUID()+"@gmail.com";
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.message").exists());
		// should not have created code bucket
		assertThat(redisson.getBucket("flashreserve:recovery:code:"+email.toLowerCase()).isExists()).isFalse();
	}

	@Test void otpNotReturnedInResponse() throws Exception{
		String email=ensureUser("fp-notreturn"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		var res=mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}"))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		assertThat(res).doesNotContain(capturedOtp != null ? capturedOtp : "no-otp");
		assertThat(res).doesNotContain("otp");
	}

	@Test void wrongOtpRejected() throws Exception{
		String email=ensureUser("fp-wrong"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\"000000\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test void expiredOtpRejected() throws Exception{
		String email=ensureUser("fp-expire"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		String code=capturedOtp;
		Thread.sleep(2500);
		mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\""+code+"\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test void maxAttemptsEnforced() throws Exception{
		String email=ensureUser("fp-max"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		for(int i=0;i<2;i++) mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\"111111\"}")).andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\"111111\"}")).andExpect(status().isTooManyRequests());
	}

	@Test void resendCooldownEnforced() throws Exception{
		String email=ensureUser("fp-cooldown"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isTooManyRequests());
	}

	@Test void resendInvalidatesPrevious() throws Exception{
		String email=ensureUser("fp-resend"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		String first=capturedOtp;
		Thread.sleep(1200);
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		String second=capturedOtp;
		assertThat(first).isNotEqualTo(second);
		mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\""+first+"\"}")).andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\""+second+"\"}")).andExpect(status().isOk());
	}

	@Test void successfulVerificationCreatesToken() throws Exception{
		String email=ensureUser("fp-success"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		String code=capturedOtp;
		var res=mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\""+code+"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.recoveryToken").isNotEmpty()).andReturn();
		String token=com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.recoveryToken");
		assertThat(token).isNotBlank();
	}

	@Test void resetRejectedWithoutVerification() throws Exception{
		String email=ensureUser("fp-nverify"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		mockMvc.perform(post("/api/auth/forgot-password/reset").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"recoveryToken\":\"fake-token\",\"newPassword\":\"new-password-123\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test void resetRejectedWithExpiredToken() throws Exception{
		String email=ensureUser("fp-exp-token"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		String code=capturedOtp;
		var res=mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\""+code+"\"}")).andExpect(status().isOk()).andReturn();
		String token=com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.recoveryToken");
		// delete token to simulate expiry
		redisson.getBucket("flashreserve:recovery:token:"+token).delete();
		mockMvc.perform(post("/api/auth/forgot-password/reset").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"recoveryToken\":\""+token+"\",\"newPassword\":\"new-password-123\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test void successfulReset() throws Exception{
		String email=ensureUser("fp-reset"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		String code=capturedOtp;
		var res=mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\""+code+"\"}")).andExpect(status().isOk()).andReturn();
		String token=com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.recoveryToken");
		mockMvc.perform(post("/api/auth/forgot-password/reset").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"recoveryToken\":\""+token+"\",\"newPassword\":\"new-password-123\"}"))
				.andExpect(status().isOk());
	}

	@Test void tokenCannotBeReused() throws Exception{
		String email=ensureUser("fp-reuse"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		String code=capturedOtp;
		var res=mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\""+code+"\"}")).andExpect(status().isOk()).andReturn();
		String token=com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.recoveryToken");
		mockMvc.perform(post("/api/auth/forgot-password/reset").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"recoveryToken\":\""+token+"\",\"newPassword\":\"new-password-123\"}")).andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/forgot-password/reset").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"recoveryToken\":\""+token+"\",\"newPassword\":\"another-pass-123\"}")).andExpect(status().isBadRequest());
	}

	@Test void bcryptAndOldFailsNewWorks() throws Exception{
		String email=ensureUser("fp-bcrypt"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/forgot-password/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		String code=capturedOtp;
		var res=mockMvc.perform(post("/api/auth/forgot-password/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\""+code+"\"}")).andExpect(status().isOk()).andReturn();
		String token=com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.recoveryToken");
		mockMvc.perform(post("/api/auth/forgot-password/reset").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"recoveryToken\":\""+token+"\",\"newPassword\":\"brand-new-123\"}")).andExpect(status().isOk());
		var user=userRepository.findByEmail(email).orElseThrow();
		assertThat(user.getPassword()).startsWith("$2");
		assertThat(passwordEncoder.matches("brand-new-123", user.getPassword())).isTrue();
		mockMvc.perform(post("/api/auth/login").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"password\":\"old-password-123\"}")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/auth/login").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"password\":\"brand-new-123\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
	}

	@Test void registrationStillWorks() throws Exception{
		String email="fp-reg-still"+UUID.randomUUID().toString().substring(0,6)+"@gmail.com";
		String ip=randomIp();
		// use registration flow
		redisson.getBucket("flashreserve:otp:verified:"+email.toLowerCase()).delete();
		// send registration OTP
		mockMvc.perform(post("/api/auth/register/send-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Test\",\"email\":\""+email+"\"}")).andExpect(status().isAccepted());
		// capturedOtp is from recovery mock, but registration uses sendOtpEmail not sendPasswordResetEmail, need to capture both - we did capture both with same var via both mocks? Actually we mock both to same var, so registration OTP also captured.
		String code=capturedOtp;
		mockMvc.perform(post("/api/auth/register/verify-otp").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"code\":\""+code+"\"}")).andExpect(status().isOk());
		mockMvc.perform(post("/api/auth/register").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Test\",\"email\":\""+email+"\",\"password\":\"password-123\"}")).andExpect(status().isCreated());
	}

	@Test void loginStillWorks() throws Exception{
		String email=ensureUser("fp-login"+UUID.randomUUID().toString().substring(0,4)+"@gmail.com");
		String ip=randomIp();
		mockMvc.perform(post("/api/auth/login").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+email+"\",\"password\":\"old-password-123\"}")).andExpect(status().isOk());
	}
}
