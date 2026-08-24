package com.soubhagya.flashreserve.controller;

import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.event.EventResponse;
import com.soubhagya.flashreserve.dto.event.SeatResponse;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.service.EventService;
import com.soubhagya.flashreserve.service.SeatService;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

	private final EventService eventService;

	private final SeatService seatService;

	@GetMapping
	PagedModel<EventResponse> listPublished(
			@PageableDefault(size = 20, sort = "eventDate", direction = Sort.Direction.ASC) Pageable pageable) {
		return new PagedModel<>(eventService.getPublishedEvents(pageable).map(EventResponse::from));
	}

	@GetMapping("/{eventId}")
	EventResponse getPublished(@PathVariable UUID eventId) {
		return EventResponse.from(eventService.getPublishedEvent(eventId));
	}

	@GetMapping("/{eventId}/seats")
	List<SeatResponse> listSeats(@PathVariable UUID eventId,
			@RequestParam(required = false) SeatStatus status) {
		eventService.getPublishedEvent(eventId);
		List<Seat> seats = (status == null)
				? seatService.getSeatsForEvent(eventId)
				: seatService.getSeatsForEvent(eventId, status);
		return seats.stream().map(SeatResponse::from).toList();
	}

}
