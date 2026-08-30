package com.soubhagya.flashreserve.dto.event;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateEventRequest(

		@NotBlank(message = "Name is required")
		@Size(max = 200, message = "Name must not exceed 200 characters")
		String name,

		@Size(max = 2000, message = "Description must not exceed 2000 characters")
		String description,

		@NotBlank(message = "Venue is required")
		@Size(max = 255, message = "Venue must not exceed 255 characters")
		String venue,

		@NotNull(message = "Event date is required")
		Instant eventDate,

		@NotNull(message = "Ticket price is required")
		@DecimalMin(value = "0.01", message = "Ticket price must be positive")
		BigDecimal ticketPrice) {

}
