package com.soubhagya.flashreserve.controller;

import java.util.UUID;

import com.soubhagya.flashreserve.dto.booking.ReservationResponse;
import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.security.UserPrincipal;
import com.soubhagya.flashreserve.service.BookingService;
import com.soubhagya.flashreserve.service.RateLimitService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/events/{eventId}/seats/{seatId}/reservations")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Reservations", description = """
		The reservation hot path: puts an AVAILABLE seat on a short temporary hold \
		for the authenticated USER. Protected by a per-user distributed rate limit \
		(429 when exhausted) and a per-seat Redis distributed lock, with PostgreSQL \
		optimistic locking as the final correctness authority.""")
public class ReservationController {

	private final BookingService bookingService;

	private final RateLimitService rateLimitService;

	@PostMapping
	@Operation(summary = "Reserve a seat (temporary hold)",
			description = """
				Holds an AVAILABLE seat for the authenticated user and creates a \
				PENDING booking with a fixed expiration time. The hold is enforced \
				by a per-seat Redis distributed lock plus a short PostgreSQL \
				transaction guarded by optimistic locking; an unavailable seat \
				returns 409 even under flash-sale contention. Unpaid holds expire \
				automatically and release the seat. Complete the purchase via the \
				Payments endpoints before the hold expires.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Seat held; PENDING booking created with its expiration time"),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but not allowed (non-USER role)"),
			@ApiResponse(responseCode = "404", description = "Published event or seat not found"),
			@ApiResponse(responseCode = "409", description = "Seat is no longer available (held or booked), possibly due to a concurrent reservation"),
			@ApiResponse(responseCode = "429", description = "Reservation rate limit exceeded for this user (Retry-After header set)", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "503", description = "Redis lock service unavailable; the reservation is refused rather than made without locking")
	})
	ResponseEntity<ReservationResponse> reserve(@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID eventId, @PathVariable UUID seatId) {
		rateLimitService.checkReservationLimit(principal.id());
		ReservationResponse response = bookingService.reserve(principal.id(), eventId, seatId);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

}
