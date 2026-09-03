package com.soubhagya.flashreserve.dto.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.Payment;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.PaymentStatus;

/**
 * Admin-facing booking representation for the read-only ADMIN booking API.
 * Extends the owner view with the booker identity and a nullable payment
 * summary. Deliberately excludes credentials and secrets (password, JWT,
 * Razorpay key material) and the provider order id, which no existing
 * response exposes.
 */
public record AdminBookingResponse(UUID bookingId, BookingStatus status, Instant expiresAt,
		Instant createdAt, Instant updatedAt, UUID eventId, String eventName, String venue,
		Instant eventDate, UUID seatId, String seatNumber, UUID bookerId, String bookerName,
		String bookerEmail, PaymentSummary payment) {

	/**
	 * Nullable payment summary; absent when the booking has no payment row
	 * yet, which is a normal state for a fresh PENDING hold.
	 */
	public record PaymentSummary(String paymentReference, BigDecimal amount,
			PaymentStatus paymentStatus, String razorpayPaymentId) {

		public static PaymentSummary from(Payment payment) {
			return new PaymentSummary(payment.getPaymentReference(), payment.getAmount(),
					payment.getStatus(), payment.getRazorpayPaymentId());
		}
	}

	public static AdminBookingResponse from(Booking booking, Payment payment) {
		return new AdminBookingResponse(booking.getId(), booking.getStatus(), booking.getExpiresAt(),
				booking.getCreatedAt(), booking.getUpdatedAt(), booking.getEvent().getId(),
				booking.getEvent().getName(), booking.getEvent().getVenue(),
				booking.getEvent().getEventDate(), booking.getSeat().getId(),
				booking.getSeat().getSeatNumber(), booking.getUser().getId(),
				booking.getUser().getName(), booking.getUser().getEmail(),
				payment == null ? null : PaymentSummary.from(payment));
	}

}