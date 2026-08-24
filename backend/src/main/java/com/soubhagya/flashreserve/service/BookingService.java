package com.soubhagya.flashreserve.service;

import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.repository.BookingRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BookingService {

	private final BookingRepository bookingRepository;

	public Booking getById(UUID id) {
		return bookingRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + id));
	}

	public List<Booking> getBookingsByUser(UUID userId) {
		return bookingRepository.findByUserId(userId);
	}

	public List<Booking> getBookingsByEvent(UUID eventId) {
		return bookingRepository.findByEventId(eventId);
	}

}
