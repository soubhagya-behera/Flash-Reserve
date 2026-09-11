package com.soubhagya.flashreserve.exception;

import org.springframework.http.HttpStatus;

/**
 * Domain error for OTP flows: wrong code, expired, too many attempts,
 * resend cooldown, or missing verification. Always maps to a safe
 * user-facing message; never includes OTP values or internals.
 */
public class OtpVerificationException extends RuntimeException {

	private final HttpStatus status;

	public OtpVerificationException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
