package com.soubhagya.flashreserve.controller;

import java.util.UUID;

import com.soubhagya.flashreserve.dto.booking.BookingResponse;
import com.soubhagya.flashreserve.security.UserPrincipal;
import com.soubhagya.flashreserve.service.BookingService;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;

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
public class BookingController {

	private final BookingService bookingService;

	@GetMapping
	PagedModel<BookingResponse> listOwn(@AuthenticationPrincipal UserPrincipal principal,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return new PagedModel<>(
				bookingService.getBookingsByUser(principal.id(), pageable).map(BookingResponse::from));
	}

	@GetMapping("/{bookingId}")
	BookingResponse getOwn(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID bookingId) {
		return BookingResponse.from(bookingService.getOwnedBooking(bookingId, principal.id()));
	}

	@PostMapping("/{bookingId}/cancel")
	BookingResponse cancel(@AuthenticationPrincipal UserPrincipal principal, @PathVariable UUID bookingId) {
		return BookingResponse.from(bookingService.cancelBooking(bookingId, principal.id()));
	}

}