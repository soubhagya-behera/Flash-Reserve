package com.soubhagya.flashreserve.controller;

import com.soubhagya.flashreserve.dto.auth.AuthResponse;
import com.soubhagya.flashreserve.dto.auth.LoginRequest;
import com.soubhagya.flashreserve.dto.auth.RegisterRequest;
import com.soubhagya.flashreserve.service.AuthService;
import com.soubhagya.flashreserve.service.RateLimitService;

import org.springframework.http.HttpStatus;
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
public class AuthController {

	private final AuthService authService;

	private final RateLimitService rateLimitService;

	@PostMapping("/register")
	ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
			HttpServletRequest httpRequest) {
		rateLimitService.checkRegistrationLimit(clientIp(httpRequest));
		return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
	}

	@PostMapping("/login")
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
