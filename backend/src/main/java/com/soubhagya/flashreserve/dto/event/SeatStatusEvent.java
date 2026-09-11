package com.soubhagya.flashreserve.dto.event;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.SeatStatus;

/**
 * Public seat-state event fanned out over SSE (and Redis across instances).
 * Minimal by design: exactly six keys, no user/booking/payment/JWT/Redis data.
 * Ordering is the authoritative Seat {@code @Version} value captured after the
 * committing flush; the frontend ignores stale versions for the same seat.
 */
public record SeatStatusEvent(UUID eventId, UUID seatId, String seatNumber, SeatStatus status, long seatVersion,
		Instant expiresAt) {
}
