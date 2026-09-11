package com.soubhagya.flashreserve.controller;

import java.util.Map;

import com.soubhagya.flashreserve.dto.auth.ForgotPasswordResetRequest;
import com.soubhagya.flashreserve.dto.auth.ForgotPasswordSendRequest;
import com.soubhagya.flashreserve.dto.auth.VerifyOtpRequest;
import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.service.PasswordRecoveryService;
import com.soubhagya.flashreserve.service.RateLimitService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth/forgot-password")
@RequiredArgsConstructor
@Tag(name = "Password Recovery")
public class ForgotPasswordController {

	private final PasswordRecoveryService recoveryService;
	private final RateLimitService rateLimitService;

	@PostMapping("/send-otp")
	@Operation(summary = "Send password-reset OTP",
			description = "Generic response: always 202, never reveals account existence.")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "If account exists, OTP sent"),
			@ApiResponse(responseCode = "400", description = "Validation failed"),
			@ApiResponse(responseCode = "429", description = "Rate limit/cooldown")
	})
	ResponseEntity<Map<String, String>> sendOtp(@Valid @RequestBody ForgotPasswordSendRequest req,
			HttpServletRequest http) {
		rateLimitService.checkRegistrationLimit(clientIp(http));
		recoveryService.sendOtp(req.email());
		return ResponseEntity.accepted().body(Map.of("message",
				"If an account exists for this email, a verification code has been sent."));
	}

	@PostMapping("/verify-otp")
	@Operation(summary = "Verify password-reset OTP and issue recovery token")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "OTP verified, recoveryToken returned"),
			@ApiResponse(responseCode = "400", description = "Invalid/expired"),
			@ApiResponse(responseCode = "429", description = "Too many attempts")
	})
	ResponseEntity<Map<String, String>> verifyOtp(@Valid @RequestBody VerifyOtpRequest req,
			HttpServletRequest http) {
		rateLimitService.checkRegistrationLimit(clientIp(http));
		String token = recoveryService.verifyOtp(req.email(), req.code());
		return ResponseEntity.ok(Map.of("recoveryToken", token));
	}

	@PostMapping("/reset")
	@Operation(summary = "Reset password with recovery token")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Password reset"),
			@ApiResponse(responseCode = "400", description = "Invalid/expired token or weak password")
	})
	ResponseEntity<Map<String, String>> reset(@Valid @RequestBody ForgotPasswordResetRequest req,
			HttpServletRequest http) {
		rateLimitService.checkRegistrationLimit(clientIp(http));
		recoveryService.resetPassword(req.email(), req.recoveryToken(), req.newPassword());
		return ResponseEntity.ok(Map.of("message", "Password reset successfully"));
	}

	private static String clientIp(HttpServletRequest req) {
		return req.getRemoteAddr();
	}
}
