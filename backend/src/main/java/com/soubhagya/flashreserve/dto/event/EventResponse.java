package com.soubhagya.flashreserve.dto.event;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.enums.EventStatus;

public record EventResponse(UUID id, String name, String description, String venue, Instant eventDate,
		int totalSeats, EventStatus status, Instant createdAt) {

	public static EventResponse from(Event event) {
		return new EventResponse(event.getId(), event.getName(), event.getDescription(), event.getVenue(),
				event.getEventDate(), event.getTotalSeats(), event.getStatus(), event.getCreatedAt());
	}

}
