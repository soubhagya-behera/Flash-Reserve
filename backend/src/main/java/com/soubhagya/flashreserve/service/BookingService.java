package com.soubhagya.flashreserve.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.config.ReservationProperties;
import com.soubhagya.flashreserve.dto.booking.ReservationResponse;
import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.exception.InvalidStateTransitionException;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;
import com.soubhagya.flashreserve.repository.UserRepository;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BookingService {

	private final BookingRepository bookingRepository;

	private final EventRepository eventRepository;

	private final SeatRepository seatRepository;

	private final UserRepository userRepository;

	private final ReservationProperties reservationProperties;

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

	@Transactional
	public ReservationResponse reserve(UUID userId, UUID eventId, UUID seatId) {
		Event event = eventRepository.findById(eventId)
				.filter(candidate -> candidate.getStatus() == EventStatus.PUBLISHED)
				.orElseThrow(() -> new ResourceNotFoundException("Event not found: " + eventId));

		Seat seat = seatRepository.findByIdAndEventId(seatId, eventId)
				.orElseThrow(() -> new ResourceNotFoundException("Seat not found for event " + eventId));

		if (seat.getStatus() != SeatStatus.AVAILABLE) {
			throw new InvalidStateTransitionException("Seat is no longer available.");
		}

		seat.setStatus(SeatStatus.HELD);
		try {
			seatRepository.saveAndFlush(seat);
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			throw new InvalidStateTransitionException("Seat is no longer available.");
		}

		User user = userRepository.getReferenceById(userId);
		Booking booking = new Booking(user, event, seat,
				Instant.now().plus(reservationProperties.holdDuration()));
		booking = bookingRepository.saveAndFlush(booking);

		return ReservationResponse.from(booking);
	}

	/**
	 * Expires a single due hold: Booking PENDING -> EXPIRED together with
	 * Seat HELD -> AVAILABLE in one transaction. If the seat row was modified
	 * concurrently, the optimistic lock fails and BOTH changes roll back;
	 * the caller retries on the next pass with fresh state, so a newer seat
	 * state is never silently overwritten.
	 */
	@Transactional
	public boolean expireIfDue(UUID bookingId) {
		Booking booking = bookingRepository.findById(bookingId).orElse(null);
		if (booking == null || !isDue(booking)) {
			return false;
		}
		Seat seat = booking.getSeat();
		if (seat.getStatus() == SeatStatus.HELD) {
			seat.setStatus(SeatStatus.AVAILABLE);
			seatRepository.saveAndFlush(seat);
		}
		booking.setStatus(BookingStatus.EXPIRED);
		return true;
	}

	private boolean isDue(Booking booking) {
		return booking.getStatus() == BookingStatus.PENDING
				&& booking.getExpiresAt() != null
				&& booking.getExpiresAt().isBefore(Instant.now());
	}

}
