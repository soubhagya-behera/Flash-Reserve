package com.soubhagya.flashreserve.controller;

import java.net.URI;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.event.CreateEventRequest;
import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.dto.event.EventResponse;
import com.soubhagya.flashreserve.dto.event.UpdateEventRequest;
import com.soubhagya.flashreserve.service.EventService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

}
