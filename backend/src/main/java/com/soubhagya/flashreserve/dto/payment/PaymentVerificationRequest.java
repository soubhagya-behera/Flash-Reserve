package com.soubhagya.flashreserve.dto.payment;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Client-supplied provider result for a completed (or failed) checkout. The
 * server never trusts these to confirm a booking: the order id must belong to
 * this booking's stored payment, the signature must be authentic, and the
 * booking must still be PENDING with its seat HELD. The client cannot set a
 * success outcome - only the provider signature proves it.
 *
 * {@code providerStatus} is optional; "FAILED" reports an unsuccessful
 * checkout so the booking can be released consistently. Anything else is
 * treated as a success attempt.
 */
public record PaymentVerificationRequest(

		@NotBlank(message = "Razorpay order id is required")
		@Size(max = 64, message = "Razorpay order id is too long")
		String razorpayOrderId,

		@NotBlank(message = "Razorpay payment id is required")
		@Size(max = 64, message = "Razorpay payment id is too long")
		String razorpayPaymentId,

		@NotBlank(message = "Razorpay signature is required")
		@Size(max = 256, message = "Razorpay signature is too long")
		String razorpaySignature,

		@Size(max = 20, message = "Provider status is invalid")
		String providerStatus) {

	/** Normalises the optional provider-reported outcome. */
	public boolean isFailed() {
		return "FAILED".equalsIgnoreCase(normalize(providerStatus));
	}

	private static String normalize(String value) {
		return value == null ? "" : value.toUpperCase(Locale.ROOT);
	}

}
