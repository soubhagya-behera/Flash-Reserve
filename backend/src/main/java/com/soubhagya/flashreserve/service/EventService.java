package com.soubhagya.flashreserve.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.event.CreateEventRequest;
import com.soubhagya.flashreserve.dto.event.EventResponse;
import com.soubhagya.flashreserve.dto.event.UpdateEventRequest;
import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.exception.InvalidStateTransitionException;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class EventService {

	private final EventRepository eventRepository;

	private final SeatRepository seatRepository;

	public Event getById(UUID id) {
		return eventRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Event not found: " + id));
	}

	/**
	 * Returns every event (DRAFT, PUBLISHED, CANCELLED or COMPLETED) for the
	 * ADMIN catalog, optionally narrowed to one status, using the standard
	 * Spring pagination conventions ({@code page}, {@code size}, {@code sort}).
	 * Unlike the public catalog this never filters by status implicitly, so an
	 * admin can see drafts and cancelled events while the public API stays
	 * PUBLISHED-only.
	 */
	public Page<Event> getEvents(EventStatus status, Pageable pageable) {
		if (status == null) {
			return eventRepository.findAll(pageable);
		}
		return eventRepository.findByStatus(status, pageable);
	}

	/**
	 * Returns one event by id regardless of its status. Unknown ids throw
	 * {@link ResourceNotFoundException}, indistinguishable from the public path.
	 */
	public Event getEventById(UUID id) {
		return getById(id);
	}

	public Page<Event> getPublishedEvents(Pageable pageable) {
		return eventRepository.findByStatus(EventStatus.PUBLISHED, pageable);
	}

	public Event getPublishedEvent(UUID id) {
		Event event = getById(id);
		if (event.getStatus() != EventStatus.PUBLISHED) {
			throw new ResourceNotFoundException("Event not found: " + id);
		}
		return event;
	}

	@Transactional
	public EventResponse create(CreateEventRequest request) {
		Event event = new Event(request.name(), request.description(), request.venue(),
				request.eventDate(), request.totalSeats());
		event.setTicketPrice(request.ticketPrice());
		event = eventRepository.saveAndFlush(event);

		List<Seat> seats = new ArrayList<>(request.totalSeats());
		for (int i = 1; i <= request.totalSeats(); i++) {
			seats.add(new Seat(event, String.format("S%03d", i)));
		}
		seatRepository.saveAll(seats);

		return EventResponse.from(event);
	}

	@Transactional
	public EventResponse update(UUID id, UpdateEventRequest request) {
		Event event = getById(id);
		// Editing an existing event (which may legitimately be in the past) is
		// allowed; only MOVING the date into the past is rejected.
		if (!request.eventDate().equals(event.getEventDate())
				&& request.eventDate().isBefore(Instant.now())) {
			throw new InvalidStateTransitionException("Event date cannot be moved into the past");
		}
		event.setName(request.name());
		event.setDescription(request.description());
		event.setVenue(request.venue());
		event.setEventDate(request.eventDate());
		event.setTicketPrice(request.ticketPrice());
		return EventResponse.from(event);
	}

	@Transactional
	public EventResponse publish(UUID id) {
		Event event = getById(id);
		if (event.getStatus() != EventStatus.DRAFT) {
			throw new InvalidStateTransitionException(
					"Cannot publish event in status " + event.getStatus());
		}
		event.setStatus(EventStatus.PUBLISHED);
		return EventResponse.from(event);
	}

	@Transactional
	public EventResponse cancel(UUID id) {
		Event event = getById(id);
		if (event.getStatus() != EventStatus.DRAFT && event.getStatus() != EventStatus.PUBLISHED) {
			throw new InvalidStateTransitionException(
					"Cannot cancel event in status " + event.getStatus());
		}
		event.setStatus(EventStatus.CANCELLED);
		return EventResponse.from(event);
	}

}
