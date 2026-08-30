package com.soubhagya.flashreserve.dto.payment;

import java.math.BigDecimal;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.PaymentStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;

/**
 * Result of a payment verification. Includes the resulting booking/payment/
 * seat states so the client never needs to guess whether the purchase went
 * through.
 */
public record PaymentConfirmationResponse(UUID bookingId, String paymentReference, String razorpayPaymentId,
		BigDecimal amount, PaymentStatus paymentStatus, BookingStatus bookingStatus, SeatStatus seatStatus) {

}
