package com.soubhagya.flashreserve.controller;

import java.util.UUID;

import com.soubhagya.flashreserve.dto.booking.ReservationResponse;
import com.soubhagya.flashreserve.security.UserPrincipal;
import com.soubhagya.flashreserve.service.BookingService;

import org.springframework.http.HttpStatus;
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
public class ReservationController {

	private final BookingService bookingService;

	@PostMapping
	ResponseEntity<ReservationResponse> reserve(@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID eventId, @PathVariable UUID seatId) {
		ReservationResponse response = bookingService.reserve(principal.id(), eventId, seatId);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

}
