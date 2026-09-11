package com.soubhagya.flashreserve.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

import com.soubhagya.flashreserve.exception.OtpVerificationException;

import org.springframework.http.HttpStatus;

/**
 * Shared OTP helpers — no Redis, no mail. Keeps hashing, generation
 * and Gmail validation in one place so registration and recovery
 * never drift.
 */
final class OtpSupport {

	private static final SecureRandom RANDOM = new SecureRandom();

	private OtpSupport() {}

	static String generateOtp() {
		return String.format("%06d", RANDOM.nextInt(1_000_000));
	}

	static String hashOtp(String otp) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] d = md.digest(otp.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(d);
		} catch (Exception ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	static String normalizeEmail(String email) {
		return email == null ? "" : email.trim().toLowerCase();
	}

	static void validateGmail(String email) {
		if (email == null || !email.toLowerCase().endsWith("@gmail.com")) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST,
					"Only Gmail addresses (@gmail.com) are allowed");
		}
		if (email.length() > 255 || email.length() < 7) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST, "Invalid Gmail address");
		}
	}

	static void validateName(String name) {
		if (name == null || name.isBlank()) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST, "Name is required");
		}
		if (name.length() > 100) {
			throw new OtpVerificationException(HttpStatus.BAD_REQUEST, "Name must not exceed 100 characters");
		}
	}
}
