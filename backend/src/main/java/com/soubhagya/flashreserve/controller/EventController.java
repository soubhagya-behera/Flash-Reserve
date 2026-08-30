package com.soubhagya.flashreserve.controller;

import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.event.EventResponse;
import com.soubhagya.flashreserve.dto.event.SeatResponse;
import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.service.EventService;
import com.soubhagya.flashreserve.service.SeatService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;

import org.springframework.http.MediaType;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = """
		Public, read-only browsing of published events and their seat maps. No JWT \
		required. Draft, cancelled and completed events are never returned.""")
public class EventController {

	private final EventService eventService;

	private final SeatService seatService;

	@GetMapping
	@Operation(summary = "List published events",
			description = """
				Public, paginated catalog of PUBLISHED events, sorted by event date \
				ascending. Supports standard Spring pagination and sorting query \
				parameters: page (0-based), size, and sort (e.g. sort=eventDate,desc). \
				Response is a Spring PagedModel wrapper.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Page of published events"),
			@ApiResponse(responseCode = "400", description = "Invalid pagination or sort parameter", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class)))
	})
	PagedModel<EventResponse> listPublished(
			@PageableDefault(size = 20, sort = "eventDate", direction = Sort.Direction.ASC) Pageable pageable) {
		return new PagedModel<>(eventService.getPublishedEvents(pageable).map(EventResponse::from));
	}

	@GetMapping("/{eventId}")
	@Operation(summary = "Get a published event",
			description = "Public detail view of a single PUBLISHED event by its UUID.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Published event found"),
			@ApiResponse(responseCode = "400", description = "eventId is not a valid UUID", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "No published event with this id (unpublished events are indistinguishable from missing ones)")
	})
	EventResponse getPublished(@PathVariable UUID eventId) {
		return EventResponse.from(eventService.getPublishedEvent(eventId));
	}

	@GetMapping("/{eventId}/seats")
	@Operation(summary = "List seats for a published event",
			description = """
				Public seat map for a published event, optionally filtered by seat \
				status (AVAILABLE, HELD or BOOKED) via the status query parameter. \
				HELD seats are on a temporary hold and may become available again.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Seat list for the event"),
			@ApiResponse(responseCode = "400", description = "Invalid eventId UUID or unknown status filter value", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "No published event with this id")
	})
	List<SeatResponse> listSeats(@PathVariable UUID eventId,
			@RequestParam(required = false) SeatStatus status) {
		eventService.getPublishedEvent(eventId);
		List<Seat> seats = (status == null)
				? seatService.getSeatsForEvent(eventId)
				: seatService.getSeatsForEvent(eventId, status);
		return seats.stream().map(SeatResponse::from).toList();
	}

}
