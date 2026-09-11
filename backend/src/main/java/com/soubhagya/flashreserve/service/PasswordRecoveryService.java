package com.soubhagya.flashreserve.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.soubhagya.flashreserve.config.RegistrationOtpProperties;
import com.soubhagya.flashreserve.exception.OtpVerificationException;
import com.soubhagya.flashreserve.repository.UserRepository;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Forgot-password flow reusing the same Redis+hash OTP core as registration.
 * Keys are isolated under {@code flashreserve:recovery:*}.
 * Generic response must not reveal account existence.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordRecoveryService {

	private static final String KEY_CODE = "flashreserve:recovery:code:";
	private static final String KEY_SENDCOUNT = "flashreserve:recovery:sendcount:";
	private static final String KEY_TOKEN = "flashreserve:recovery:token:";

	private static final Duration TOKEN_TTL = Duration.ofMinutes(10);

	private final RedissonClient redisson;
	private final RegistrationOtpProperties props;
	private final UserRepository userRepository;
	private final EmailService emailService;
	private final PasswordEncoder passwordEncoder;

	public void sendOtp(String rawEmail) {
		String email = OtpSupport.normalizeEmail(rawEmail);
		OtpSupport.validateGmail(email);
		enforceSendLimits(email);
		// Generic response: do not reveal existence, but don't create OTP for non-existent.
		if (!userRepository.existsByEmail(email)) {
			return;
		}
		String otp = OtpSupport.generateOtp();
		String hash = OtpSupport.hashOtp(otp);
		RecoveryState state = new RecoveryState(email, hash, props.maxAttempts(), System.currentTimeMillis());
		codeBucket(email).set(state, props.ttl().toMillis(), TimeUnit.MILLISECONDS);
		try {
			emailService.sendPasswordResetEmail(email, otp);
		} catch (RuntimeException ex) {
			throw ex;
		}
	}

	/**
	 * Verifies OTP and returns an opaque recovery token.
	 */
	public String verifyOtp(String rawEmail, String code) {
		String email = OtpSupport.normalizeEmail(rawEmail);
		OtpSupport.validateGmail(email);
		if (code == null || !code.matches("^[0-9]{6}$")) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST, "Invalid verification code");
		}
		RBucket<RecoveryState> bucket = codeBucket(email);
		RecoveryState state = bucket.get();
		if (state == null) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST,
					"Verification code has expired or was not requested. Please request a new code.");
		}
		if (state.attemptsRemaining <= 0) {
			bucket.delete();
			throw new OtpVerificationException(HttpStatus.TOO_MANY_REQUESTS,
					"Too many incorrect attempts. Please request a new code.");
		}
		String hash = OtpSupport.hashOtp(code);
		if (!hash.equals(state.otpHash)) {
			int remaining = state.attemptsRemaining - 1;
			if (remaining <= 0) {
				bucket.delete();
				throw new OtpVerificationException(HttpStatus.TOO_MANY_REQUESTS,
						"Too many incorrect attempts. Please request a new code.");
			}
			RecoveryState updated = new RecoveryState(state.email, state.otpHash, remaining, state.createdAtMs);
			long ttl = bucket.remainTimeToLive();
			if (ttl <= 0) ttl = props.ttl().toMillis();
			bucket.set(updated, ttl, TimeUnit.MILLISECONDS);
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST, "Invalid verification code");
		}
		bucket.delete();
		String token = UUID.randomUUID().toString();
		redisson.getBucket(KEY_TOKEN + token).set(email, TOKEN_TTL.toMillis(), TimeUnit.MILLISECONDS);
		return token;
	}

	public void resetPassword(String rawEmail, String token, String newPassword) {
		String email = OtpSupport.normalizeEmail(rawEmail);
		OtpSupport.validateGmail(email);
		if (token == null || token.isBlank()) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST, "Recovery authorization is required");
		}
		if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 72) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST, "Password must be between 8 and 72 characters");
		}
		RBucket<String> tokenBucket = redisson.getBucket(KEY_TOKEN + token);
		String storedEmail = tokenBucket.get();
		if (storedEmail == null || !storedEmail.equals(email)) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST,
					"Recovery authorization has expired or is invalid. Please request a new code.");
		}
		var user = userRepository.findByEmail(email)
				.orElseThrow(() -> new OtpVerificationException(HttpStatus.BAD_REQUEST, "Account not found"));
		user.setPassword(passwordEncoder.encode(newPassword));
		userRepository.save(user);
		tokenBucket.delete();
	}

	private void enforceSendLimits(String email) {
		RBucket<RecoveryState> code = codeBucket(email);
		RecoveryState existing = code.get();
		if (existing != null) {
			long elapsed = System.currentTimeMillis() - existing.createdAtMs;
			long cooldownMs = props.resendCooldown().toMillis();
			if (elapsed < cooldownMs) {
				long waitSec = (cooldownMs - elapsed + 999) / 1000;
				throw new OtpVerificationException(HttpStatus.TOO_MANY_REQUESTS,
						"Please wait " + waitSec + " seconds before requesting another code");
			}
		}
		RAtomicLong counter = redisson.getAtomicLong(KEY_SENDCOUNT + email);
		long count = counter.incrementAndGet();
		if (count == 1) {
			counter.expire(Duration.ofHours(1));
		}
		if (count > props.maxSendsPerHour()) {
			throw new OtpVerificationException(HttpStatus.TOO_MANY_REQUESTS,
					"Too many verification emails. Please try again later");
		}
	}

	private RBucket<RecoveryState> codeBucket(String email) {
		return redisson.getBucket(KEY_CODE + email);
	}

	public record RecoveryState(String email, String otpHash, int attemptsRemaining, long createdAtMs)
			implements java.io.Serializable {}
}
