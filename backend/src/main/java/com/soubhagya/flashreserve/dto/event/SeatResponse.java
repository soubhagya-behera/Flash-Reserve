package com.soubhagya.flashreserve.dto.event;

import java.util.UUID;

import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;

public record SeatResponse(UUID id, String seatNumber, SeatStatus status) {

	public static SeatResponse from(Seat seat) {
		return new SeatResponse(seat.getId(), seat.getSeatNumber(), seat.getStatus());
	}

}
