package com.soubhagya.flashreserve.dto.payment;

import java.math.BigDecimal;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.PaymentStatus;

/**
 * Data the future frontend needs to open the Razorpay Checkout. It exposes
 * only the public client-side key id - never the key secret. The order id is
 * the provider order created server-side for the server-derived amount.
 */
public record PaymentInitiationResponse(UUID bookingId, String paymentReference, String razorpayOrderId,
		String razorpayKeyId, BigDecimal amount, String currency, PaymentStatus status) {

}
