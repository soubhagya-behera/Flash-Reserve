package com.soubhagya.flashreserve.controller;

import java.util.UUID;

import com.soubhagya.flashreserve.dto.booking.AdminBookingResponse;
import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.service.BookingService;
import com.soubhagya.flashreserve.service.PaymentService;

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

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/bookings")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Booking administration (ADMIN)", description = """
		Read-only admin view of every booking regardless of owner or status. \
		Every endpoint requires a JWT with the ADMIN role; a valid USER token \
		receives 403. Owner booking endpoints stay USER-scoped and are not \
		reachable by admins.""")
public class AdminBookingController {

	private final BookingService bookingService;

	private final PaymentService paymentService;

	@GetMapping
	@Operation(summary = "List all bookings (optionally filtered)",
			description = """
					Paginated admin catalog of every booking: PENDING, CONFIRMED, \
					EXPIRED or CANCELLED. Supports optional status and eventId \
					filters plus standard Spring pagination parameters (page, \
					size, sort); newest bookings first by default. Response is a \
					Spring PagedModel wrapper.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Page of bookings (optionally filtered)"),
			@ApiResponse(responseCode = "400", description = "Invalid pagination/sort parameter, unknown status filter value or malformed eventId", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but without the ADMIN role")
	})
	PagedModel<AdminBookingResponse> list(@RequestParam(required = false) BookingStatus status,
			@RequestParam(required = false) UUID eventId,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return new PagedModel<>(bookingService.getBookingsForAdmin(status, eventId, pageable)
				.map(this::toResponse));
	}

	@GetMapping("/{bookingId}")
	@Operation(summary = "Get one booking by id (any status, any owner)",
			description = """
					Admin detail view of a single booking with its event, seat, \
					booker identity and nullable payment summary. Unknown ids \
					return 404; there is no ownership restriction for admins.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Booking found (any status)"),
			@ApiResponse(responseCode = "400", description = "bookingId is not a valid UUID", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but without the ADMIN role"),
			@ApiResponse(responseCode = "404", description = "Booking not found")
	})
	AdminBookingResponse get(@PathVariable UUID bookingId) {
		return toResponse(bookingService.getBookingForAdmin(bookingId));
	}

	private AdminBookingResponse toResponse(Booking booking) {
		return AdminBookingResponse.from(booking,
				paymentService.findByBookingId(booking.getId()).orElse(null));
	}

}