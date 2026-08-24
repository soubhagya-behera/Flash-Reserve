package com.soubhagya.flashreserve.service;

import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.repository.SeatRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SeatService {

	private final SeatRepository seatRepository;

	public Seat getById(UUID id) {
		return seatRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Seat not found: " + id));
	}

	public List<Seat> getSeatsForEvent(UUID eventId) {
		return seatRepository.findByEventId(eventId);
	}

	public Seat getSeatForEvent(UUID eventId, String seatNumber) {
		return seatRepository.findByEventIdAndSeatNumber(eventId, seatNumber)
				.orElseThrow(() -> new ResourceNotFoundException(
						"Seat not found for event " + eventId + ": " + seatNumber));
	}

	public List<Seat> getAvailableSeats(UUID eventId) {
		return seatRepository.findByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
	}

}
