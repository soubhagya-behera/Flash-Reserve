package com.soubhagya.flashreserve.controller;

import java.net.URI;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.event.CreateEventRequest;
import com.soubhagya.flashreserve.dto.event.EventResponse;
import com.soubhagya.flashreserve.dto.event.UpdateEventRequest;
import com.soubhagya.flashreserve.service.EventService;

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
public class AdminEventController {

	private final EventService eventService;

	@PostMapping
	ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request) {
		EventResponse created = eventService.create(request);
		return ResponseEntity.created(URI.create("/api/events/" + created.id())).body(created);
	}

	@PutMapping("/{eventId}")
	EventResponse update(@PathVariable UUID eventId, @Valid @RequestBody UpdateEventRequest request) {
		return eventService.update(eventId, request);
	}

	@PatchMapping("/{eventId}/publish")
	EventResponse publish(@PathVariable UUID eventId) {
		return eventService.publish(eventId);
	}

	@PatchMapping("/{eventId}/cancel")
	EventResponse cancel(@PathVariable UUID eventId) {
		return eventService.cancel(eventId);
	}

}
