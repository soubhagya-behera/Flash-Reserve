package com.soubhagya.flashreserve.dto.booking;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;

/**
 * Client-facing booking representation. Includes only information the owner
 * of the booking is allowed to see; never exposes users, passwords, or
 * internal relationships.
 */
public record BookingResponse(UUID bookingId, UUID eventId, String eventName, String eventVenue,
		Instant eventDate, UUID seatId, String seatNumber, BookingStatus status, Instant expiresAt,
		Instant createdAt, Instant updatedAt) {

	public static BookingResponse from(Booking booking) {
		return new BookingResponse(booking.getId(), booking.getEvent().getId(), booking.getEvent().getName(),
				booking.getEvent().getVenue(), booking.getEvent().getEventDate(),
				booking.getSeat().getId(), booking.getSeat().getSeatNumber(), booking.getStatus(),
				booking.getExpiresAt(), booking.getCreatedAt(), booking.getUpdatedAt());
	}

}