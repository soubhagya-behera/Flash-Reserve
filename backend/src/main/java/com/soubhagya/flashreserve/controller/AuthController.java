package com.soubhagya.flashreserve.controller;

import com.soubhagya.flashreserve.dto.auth.AuthResponse;
import com.soubhagya.flashreserve.dto.auth.LoginRequest;
import com.soubhagya.flashreserve.dto.auth.RegisterRequest;
import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.service.AuthService;
import com.soubhagya.flashreserve.service.RateLimitService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = """
		Public registration and login. No JWT required. Both endpoints are rate \
		limited per client IP (distributed, Redis-backed) to stop brute force and \
		registration floods; a 429 with a Retry-After header is returned when the \
		budget is exhausted.""")
public class AuthController {

	private final AuthService authService;

	private final RateLimitService rateLimitService;

	@PostMapping("/register")
	@Operation(summary = "Register a new user account",
			description = """
				Creates a USER account and returns an immediate bearer JWT with the \
				user profile, so a new user can reserve without a separate login. \
				Registration is race-safe: a PostgreSQL unique constraint on email is \
				the final arbiter, so a concurrent duplicate surfaces as a 409, never \
				a 500. New accounts are always USER; there is no self-service ADMIN.""")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Account created; JWT returned"),
			@ApiResponse(responseCode = "400", description = "Validation failed (missing or malformed fields)", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "409", description = "Email already registered"),
			@ApiResponse(responseCode = "429", description = "Registration rate limit exceeded for this client IP (Retry-After header set)")
	})
	ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
			HttpServletRequest httpRequest) {
		rateLimitService.checkRegistrationLimit(clientIp(httpRequest));
		return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
	}

	@PostMapping("/login")
	@Operation(summary = "Log in with email and password",
			description = """
				Authenticates an existing account and returns a fresh bearer JWT. The \
				401 response never reveals whether the email or the password was wrong.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Authenticated; JWT returned"),
			@ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "401", description = "Invalid email or password"),
			@ApiResponse(responseCode = "429", description = "Login rate limit exceeded for this client IP (Retry-After header set)")
	})
	ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
			HttpServletRequest httpRequest) {
		rateLimitService.checkLoginLimit(clientIp(httpRequest));
		return ResponseEntity.ok(authService.login(request));
	}

	/**
	 * Identity for the public-endpoint rate limit. Remote address only:
	 * {@code X-Forwarded-For} is client-controlled and must not be trusted as
	 * a rate-limit identity unless a trusted proxy is guaranteed in front.
	 */
	private static String clientIp(HttpServletRequest request) {
		return request.getRemoteAddr();
	}

}
