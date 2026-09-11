package com.soubhagya.flashreserve.controller;

import java.time.Duration;
import java.util.UUID;

import com.soubhagya.flashreserve.exception.RateLimitExceededException;
import com.soubhagya.flashreserve.service.EventService;
import com.soubhagya.flashreserve.service.SeatUpdateHub;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.RequiredArgsConstructor;

/**
 * Public event-scoped SSE stream for seat updates. Payload mirrors the public
 * seat map (no PII); REST seats stay the source of truth and recovery.
 * Connection failures surface via EventSource errors; the client reconnects
 * once with backoff and refetches the REST snapshot.
 */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "SeatUpdates", description = "Public SSE stream of seat-state changes for one published event.")
public class SeatUpdateController {

	static final int MAX_PER_EVENT = 500;

	static final int MAX_GLOBAL = 2000;

	private final EventService eventService;

	private final SeatUpdateHub hub;

	@GetMapping(value = "/{eventId}/seat-updates", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@Operation(summary = "Stream seat updates for a published event")
	public SseEmitter stream(@PathVariable UUID eventId) {
		eventService.getPublishedEvent(eventId);
		if (hub.count(eventId) >= MAX_PER_EVENT || hub.total() >= MAX_GLOBAL) {
			throw new RateLimitExceededException(Duration.ofSeconds(30),
					"Too many live seat-update connections. Please retry shortly.");
		}
		SseEmitter emitter = new SseEmitter(0L);
		hub.add(eventId, emitter);
		return emitter;
	}

}
