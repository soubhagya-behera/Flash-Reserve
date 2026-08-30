package com.soubhagya.flashreserve.payment;

import java.math.BigDecimal;

/**
 * Narrow abstraction over the external payment provider. Its single
 * responsibility is provider interaction: creating an order for a server-side
 * amount and verifying that a completed payment is authentic.
 *
 * It deliberately knows nothing about bookings, seats, repositories, booking
 * state transitions, or authentication - those belong to PaymentService. Only
 * one real implementation (Razorpay) exists; no factory or manager layer is
 * warranted.
 */
public interface PaymentProvider {

	/**
	 * Asks the provider to create an order for the given amount (in the base
	 * currency unit, e.g. rupees) and returns the provider order id. The
	 * {@code paymentReference} is a locally generated identifier attached to
	 * the order for reconciliation.
	 */
	String createOrder(String paymentReference, BigDecimal amount);

	/**
	 * Verifies a completed payment (provider order id + payment id +
	 * signature) using the provider secret. Returns true only when the
	 * signature is authentic. Never logs the signature or the secret.
	 */
	boolean verifySignature(String orderId, String paymentId, String signature);

	/**
	 * The public, client-side identifier the provider requires to render its
	 * checkout. It is safe to expose to the browser; it is never the secret.
	 */
	String getClientKeyId();

	/**
	 * The currency code (e.g. "INR") in which orders are created and amounts
	 * are denominated.
	 */
	String getCurrency();

}
