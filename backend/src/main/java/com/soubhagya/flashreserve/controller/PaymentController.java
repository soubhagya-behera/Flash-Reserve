package com.soubhagya.flashreserve.controller;

import java.util.UUID;

import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.dto.payment.PaymentConfirmationResponse;
import com.soubhagya.flashreserve.dto.payment.PaymentInitiationResponse;
import com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest;
import com.soubhagya.flashreserve.security.UserPrincipal;
import com.soubhagya.flashreserve.service.PaymentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
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
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Payments", description = """
		Razorpay TEST MODE checkout for a held booking. Step 1: initiate to get \
		the Razorpay order id and public key id for Checkout. Step 2: verify the \
		checkout result; confirmation only happens after server-side signature \
		verification. Test mode only - no real money is involved.""")
public class PaymentController {

	private final PaymentService paymentService;

	@PostMapping("/{bookingId}/payment")
	@Operation(summary = "Initiate payment for a booking",
			description = """
				Creates (or reuses) the Razorpay order for a PENDING booking. The amount \
				comes exclusively from the event's server-side ticket price - the client \
				never supplies one. Returns the data Razorpay Checkout needs, including \
				the public key id only (never the key secret).""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Payment initiation data returned (may reuse an existing payment)"),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but not allowed (non-USER role)"),
			@ApiResponse(responseCode = "404", description = "Booking not found or not owned by the caller"),
			@ApiResponse(responseCode = "409", description = "Booking is not PENDING, so payment cannot start"),
			@ApiResponse(responseCode = "503", description = "Payment provider unavailable")
	})
	PaymentInitiationResponse initiate(@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID bookingId) {
		return paymentService.initiate(bookingId, principal.id());
	}

	@PostMapping("/{bookingId}/payment/verify")
	@Operation(summary = "Verify payment and confirm the booking",
			description = """
				Verifies the Razorpay checkout result. Confirmation requires the order id \
				to match this booking's payment and the signature to pass server-side \
				HMAC verification - a client can never claim success on its own. On \
				success the booking becomes CONFIRMED and the seat BOOKED. A reported \
				client-side checkout failure releases the hold consistently.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Verification result (confirmed booking, or consistently failed checkout)"),
			@ApiResponse(responseCode = "400", description = "Signature or order verification failed", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but not allowed (non-USER role)"),
			@ApiResponse(responseCode = "404", description = "Booking or payment not found / not owned by the caller"),
			@ApiResponse(responseCode = "409", description = "Booking or seat changed concurrently (expired, cancelled, or already booked)")
	})
	PaymentConfirmationResponse verify(@AuthenticationPrincipal UserPrincipal principal,
			@PathVariable UUID bookingId, @Valid @RequestBody PaymentVerificationRequest request) {
		return paymentService.verify(bookingId, principal.id(), request);
	}

}
