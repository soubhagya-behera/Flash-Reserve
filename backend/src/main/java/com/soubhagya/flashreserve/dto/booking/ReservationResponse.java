package com.soubhagya.flashreserve.dto.booking;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;

public record ReservationResponse(UUID bookingId, UUID eventId, UUID seatId, String seatNumber,
		BookingStatus status, Instant expiresAt, Instant createdAt) {

	public static ReservationResponse from(Booking booking) {
		return new ReservationResponse(booking.getId(), booking.getEvent().getId(), booking.getSeat().getId(),
				booking.getSeat().getSeatNumber(), booking.getStatus(), booking.getExpiresAt(),
				booking.getCreatedAt());
	}

}
