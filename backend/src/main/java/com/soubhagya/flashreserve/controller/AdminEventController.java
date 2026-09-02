package com.soubhagya.flashreserve.controller;

import java.net.URI;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.event.CreateEventRequest;
import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.dto.event.EventResponse;
import com.soubhagya.flashreserve.dto.event.UpdateEventRequest;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.service.EventService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/events")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Event administration (ADMIN)", description = """
		Admin-only event lifecycle management. Every endpoint requires a JWT with \
		the ADMIN role; a valid USER token receives 403. Creating an event also \
		creates its seat inventory atomically - seat rows are rolled back with the \
		event if creation fails.""")
public class AdminEventController {

	private final EventService eventService;

	@PostMapping
	@Operation(summary = "Create a new event (draft)",
			description = """
				Creates a DRAFT event and its full seat inventory in one transaction. \
				The event date must be in the future and the ticket price positive. \
				Drafts are invisible on the public catalog until published.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Event created as DRAFT; Location header points at the public read endpoint"),
			@ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but without the ADMIN role")
	})
	ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request) {
		EventResponse created = eventService.create(request);
		return ResponseEntity.created(URI.create("/api/events/" + created.id())).body(created);
	}

	@PutMapping("/{eventId}")
	@Operation(summary = "Update an event",
			description = "Replaces the editable fields of an event. Restricted to ADMIN.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Event updated"),
			@ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but without the ADMIN role"),
			@ApiResponse(responseCode = "404", description = "Event not found"),
			@ApiResponse(responseCode = "409", description = "Event was modified concurrently")
	})
	EventResponse update(@PathVariable UUID eventId, @Valid @RequestBody UpdateEventRequest request) {
		return eventService.update(eventId, request);
	}

	@PatchMapping("/{eventId}/publish")
	@Operation(summary = "Publish an event",
			description = "Makes a DRAFT event visible on the public catalog. Invalid state transitions (e.g. publishing a cancelled event) are rejected.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Event is now PUBLISHED"),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but without the ADMIN role"),
			@ApiResponse(responseCode = "404", description = "Event not found"),
			@ApiResponse(responseCode = "409", description = "Transition not allowed from the event's current status")
	})
	EventResponse publish(@PathVariable UUID eventId) {
		return eventService.publish(eventId);
	}

	@PatchMapping("/{eventId}/cancel")
	@Operation(summary = "Cancel an event",
			description = "Cancels a published or draft event. Invalid state transitions are rejected with a conflict.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Event is now CANCELLED"),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but without the ADMIN role"),
			@ApiResponse(responseCode = "404", description = "Event not found"),
			@ApiResponse(responseCode = "409", description = "Transition not allowed from the event's current status")
	})
	EventResponse cancel(@PathVariable UUID eventId) {
		return eventService.cancel(eventId);
	}

	@GetMapping
	@Operation(summary = "List all events (any status, optionally filtered)",
			description = """
					Admin catalog of every event regardless of status: DRAFT, PUBLISHED, \
					CANCELLED or COMPLETED. Supports standard Spring pagination parameters \
					(page, size, sort) and an optional status query parameter to narrow the \
					list. Response is a Spring PagedModel wrapper.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Page of events (optionally filtered by status)"),
			@ApiResponse(responseCode = "400", description = "Invalid pagination/sort parameter or unknown status filter value", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but without the ADMIN role")
	})
	PagedModel<EventResponse> list(@RequestParam(required = false) EventStatus status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return new PagedModel<>(
				eventService.getEvents(status, pageable).map(EventResponse::from));
	}

	@GetMapping("/{eventId}")
	@Operation(summary = "Get one event by id (any status)",
			description = """
					Admin detail view of a single event regardless of its status. Unlike \
					the public endpoint this returns DRAFT, PUBLISHED, CANCELLED and \
					COMPLETED events; unknown ids return 404.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Event found (any status)"),
			@ApiResponse(responseCode = "400", description = "eventId is not a valid UUID", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but without the ADMIN role"),
			@ApiResponse(responseCode = "404", description = "Event not found")
	})
	EventResponse get(@PathVariable UUID eventId) {
		return EventResponse.from(eventService.getEventById(eventId));
	}

}
