package com.soubhagya.flashreserve.service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.soubhagya.flashreserve.config.RegistrationOtpProperties;
import com.soubhagya.flashreserve.exception.DuplicateEmailException;
import com.soubhagya.flashreserve.exception.OtpVerificationException;
import com.soubhagya.flashreserve.repository.UserRepository;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed OTP lifecycle for Gmail registration.
 *
 * <p>Keys:
 * <ul>
 *   <li>{@code flashreserve:otp:code:<email>} — JSON state with hash, attempts, verified flag; TTL = otp ttl</li>
 *   <li>{@code flashreserve:otp:verified:<email>} — marker set after successful verification; TTL = 10m</li>
 *   <li>{@code flashreserve:otp:sendcount:<email>} — atomic counter for per-hour send cap; TTL = 1h</li>
 * </ul>
 * Previous OTP is overwritten on resend. Attempts are decremented on wrong code;
 * exhaustion deletes the code key. Verified state is single-use and consumed
 * at account creation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationOtpService {

	private static final String KEY_CODE = "flashreserve:otp:code:";
	private static final String KEY_VERIFIED = "flashreserve:otp:verified:";
	private static final String KEY_SENDCOUNT = "flashreserve:otp:sendcount:";

	private static final Duration VERIFIED_TTL = Duration.ofMinutes(10);

	private final RedissonClient redisson;
	private final RegistrationOtpProperties props;
	private final UserRepository userRepository;
	private final EmailService emailService;

	// ---- public API ----

	public void sendOtp(String rawName, String rawEmail) {
		String name = rawName == null ? "" : rawName.trim();
		String email = OtpSupport.normalizeEmail(rawEmail);
		OtpSupport.validateName(name);
		OtpSupport.validateGmail(email);
		if (userRepository.existsByEmail(email)) {
			throw new DuplicateEmailException("Email already registered");
		}
		enforceSendLimits(email);
		String otp = OtpSupport.generateOtp();
		String hash = OtpSupport.hashOtp(otp);
		OtpState state = new OtpState(name, email, hash, props.maxAttempts(), false,
				System.currentTimeMillis(), 0L);
		codeBucket(email).set(state, props.ttl().toMillis(), TimeUnit.MILLISECONDS);
		try {
			emailService.sendOtpEmail(email, otp);
		} catch (RuntimeException ex) {
			// Delivery failure should not leave a dangling code that burns the cooldown.
			// Keep the code so a retry respects cooldown, but rethrow as-is.
			throw ex;
		}
	}

	public void verifyOtp(String rawEmail, String code) {
		String email = OtpSupport.normalizeEmail(rawEmail);
		OtpSupport.validateGmail(email);
		if (code == null || !code.matches("^[0-9]{6}$")) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST, "Invalid verification code");
		}
		RBucket<OtpState> bucket = codeBucket(email);
		OtpState state = bucket.get();
		if (state == null) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST,
					"Verification code has expired or was not requested. Please request a new code.");
		}
		if (state.verified) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST,
					"This code has already been used. Please request a new code.");
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
			OtpState updated = new OtpState(state.name, state.email, state.otpHash, remaining, false,
					state.createdAtMs, 0L);
			long ttl = bucket.remainTimeToLive();
			if (ttl <= 0) ttl = props.ttl().toMillis();
			bucket.set(updated, ttl, TimeUnit.MILLISECONDS);
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST, "Invalid verification code");
		}
		// Success: mark verified, delete code, set verified marker.
		bucket.delete();
		RBucket<String> verifiedBucket = verifiedBucket(email);
		verifiedBucket.set(state.email, VERIFIED_TTL.toMillis(), TimeUnit.MILLISECONDS);
	}

	public boolean isVerified(String rawEmail) {
		String email = OtpSupport.normalizeEmail(rawEmail);
		return verifiedBucket(email).isExists();
	}

	public void consumeVerified(String rawEmail) {
		String email = OtpSupport.normalizeEmail(rawEmail);
		verifiedBucket(email).delete();
	}

	private void enforceSendLimits(String email) {
		// Cooldown: if a code bucket exists and was created recently, reject.
		RBucket<OtpState> code = codeBucket(email);
		OtpState existing = code.get();
		if (existing != null) {
			long elapsed = System.currentTimeMillis() - existing.createdAtMs;
			long cooldownMs = props.resendCooldown().toMillis();
			if (elapsed < cooldownMs) {
				long waitSec = (cooldownMs - elapsed + 999) / 1000;
				throw new OtpVerificationException(HttpStatus.TOO_MANY_REQUESTS,
						"Please wait " + waitSec + " seconds before requesting another code");
			}
		}
		// Per-hour cap.
		String countKey = KEY_SENDCOUNT + email;
		RAtomicLong counter = redisson.getAtomicLong(countKey);
		long count = counter.incrementAndGet();
		if (count == 1) {
			counter.expire(Duration.ofHours(1));
		}
		if (count > props.maxSendsPerHour()) {
			throw new OtpVerificationException(HttpStatus.TOO_MANY_REQUESTS,
					"Too many verification emails. Please try again later");
		}
	}



	private RBucket<OtpState> codeBucket(String email) {
		return redisson.getBucket(KEY_CODE + email);
	}

	private RBucket<String> verifiedBucket(String email) {
		return redisson.getBucket(KEY_VERIFIED + email);
	}

	/**
	 * Serializable state stored in Redis. Must stay serializable across
	 * Redisson restarts; keep fields primitive/strings only.
	 */
	public record OtpState(
			String name,
			String email,
			String otpHash,
			int attemptsRemaining,
			boolean verified,
			long createdAtMs,
			long verifiedAtMs) implements java.io.Serializable {
	}
}
