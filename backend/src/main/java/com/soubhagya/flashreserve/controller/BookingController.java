package com.soubhagya.flashreserve.controller;

import java.util.UUID;

import com.soubhagya.flashreserve.dto.booking.BookingResponse;
import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.security.UserPrincipal;
import com.soubhagya.flashreserve.service.BookingService;

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

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Bookings", description = """
		Owner-scoped booking management for authenticated USERs. A booking is \
		only ever reachable by its owner: missing and foreign bookings both \
		return 404, so the existence of another user's booking is never revealed.""")
public class BookingController {

	private final BookingService bookingService;

	@GetMapping
	@Operation(summary = "List the caller's bookings",
			description = """
				Paginated list of the authenticated user's own bookings, newest first. \
				Supports standard Spring pagination and sorting parameters: page, size, \
				sort. Every user sees only their own bookings.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Page of the caller's bookings"),
			@ApiResponse(responseCode = "400", description = "Invalid pagination or sort parameter", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class)))
	})
	PagedModel<BookingResponse> listOwn(@AuthenticationPrincipal UserPrincipal principal,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return new PagedModel<>(
				bookingService.getBookingsByUser(principal.id(), pageable).map(BookingResponse::from));
	}

	@GetMapping("/{bookingId}")
	@Operation(summary = "Get one of the caller's bookings",
			description = "Returns a single booking with event, seat and hold details. Foreign or unknown ids return 404.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Booking found and owned by the caller"),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "Booking not found or owned by a different user")
	})
	BookingResponse getOwn(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID bookingId) {
		return BookingResponse.from(bookingService.getOwnedBooking(bookingId, principal.id()));
	}

	@PostMapping("/{bookingId}/cancel")
	@Operation(summary = "Cancel a PENDING booking and release the seat",
			description = """
				Cancels one of the caller's PENDING bookings and releases its seat \
				(HELD back to AVAILABLE) in a single transaction. Cancelled or \
				confirmed bookings cannot be cancelled. The seat's optimistic lock \
				arbitrates against a concurrent hold expiration, so the seat state \
				stays consistent.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Booking CANCELLED and seat released"),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "Booking not found or owned by a different user"),
			@ApiResponse(responseCode = "409", description = "Booking is not PENDING, or the seat was changed concurrently (e.g. expired or paid)")
	})
	BookingResponse cancel(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID bookingId) {
		return BookingResponse.from(bookingService.cancelBooking(bookingId, principal.id()));
	}

}