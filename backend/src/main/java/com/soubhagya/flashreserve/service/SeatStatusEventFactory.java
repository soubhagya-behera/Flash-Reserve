package com.soubhagya.flashreserve.service;

import com.soubhagya.flashreserve.dto.booking.ReservationResponse;
import com.soubhagya.flashreserve.dto.event.SeatStatusEvent;
import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;

/** Builds public seat events from committed state. No database access. */
final class SeatStatusEventFactory {
	private SeatStatusEventFactory() {
	}
	static SeatStatusEvent held(ReservationResponse r, long v) {
		return new SeatStatusEvent(r.eventId(), r.seatId(), r.seatNumber(), SeatStatus.HELD, v, r.expiresAt());
	}
	static SeatStatusEvent available(Booking b) {
		return new SeatStatusEvent(b.getEvent().getId(), b.getSeat().getId(), b.getSeat().getSeatNumber(),
				SeatStatus.AVAILABLE, b.getSeat().getVersion(), null);
	}
	static SeatStatusEvent booked(Booking b) {
		return new SeatStatusEvent(b.getEvent().getId(), b.getSeat().getId(), b.getSeat().getSeatNumber(),
				SeatStatus.BOOKED, b.getSeat().getVersion(), null);
	}
}
