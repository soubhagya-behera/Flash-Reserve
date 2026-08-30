package com.soubhagya.flashreserve.controller;

import java.util.UUID;

import com.soubhagya.flashreserve.dto.payment.PaymentConfirmationResponse;
import com.soubhagya.flashreserve.dto.payment.PaymentInitiationResponse;
import com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest;
import com.soubhagya.flashreserve.security.UserPrincipal;
import com.soubhagya.flashreserve.service.PaymentService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

/**
 * Thin controller exposing the two payment endpoints the Razorpay checkout
 * flow needs. Both are owner-scoped: the authenticated principal is used and a
 * client-supplied userId is never accepted.
 */
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class PaymentController {

	private final PaymentService paymentService;

	@PostMapping("/{bookingId}/payment")
	PaymentInitiationResponse initiate(@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID bookingId) {
		return paymentService.initiate(bookingId, principal.id());
	}

	@PostMapping("/{bookingId}/payment/verify")
	PaymentConfirmationResponse verify(@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID bookingId, @Valid @RequestBody PaymentVerificationRequest request) {
		return paymentService.verify(bookingId, principal.id(), request);
	}

}
