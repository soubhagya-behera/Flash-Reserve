package com.soubhagya.flashreserve.exception;

/**
 * A submitted payment verification is invalid (bad signature, or a provider
 * order id that does not belong to this booking's payment). Maps to a 400 Bad
 * Request; a failed verification must never move a booking to CONFIRMED.
 */
public class PaymentVerificationException extends RuntimeException {

	public PaymentVerificationException(String message) {
		super(message);
	}

}
