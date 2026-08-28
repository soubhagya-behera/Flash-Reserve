package com.soubhagya.flashreserve.service;

import com.soubhagya.flashreserve.dto.auth.AuthResponse;
import com.soubhagya.flashreserve.dto.auth.LoginRequest;
import com.soubhagya.flashreserve.dto.auth.RegisterRequest;
import com.soubhagya.flashreserve.dto.auth.UserResponse;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.exception.DuplicateEmailException;
import com.soubhagya.flashreserve.security.JwtService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserService userService;

	private final PasswordEncoder passwordEncoder;

	private final JwtService jwtService;

	/**
	 * The {@code uk_users_email} unique constraint is the final race-safe
	 * protection: two concurrent registrations can both pass the pre-check,
	 * but only one insert can win. The loser surfaces as an expected 409
	 * {@link DuplicateEmailException}, never a 500.
	 */
	public AuthResponse register(RegisterRequest request) {
		if (userService.existsByEmail(request.email())) {
			throw new DuplicateEmailException("Email already registered");
		}
		String encodedPassword = passwordEncoder.encode(request.password());
		try {
			User user = userService.createUser(request.name(), request.email(),
					encodedPassword, UserRole.USER);
			return buildAuthResponse(user);
		}
		catch (DataIntegrityViolationException ex) {
			throw new DuplicateEmailException("Email already registered");
		}
	}

	public AuthResponse login(LoginRequest request) {
		User user = userService.findByEmail(request.email())
				.filter(saved -> passwordEncoder.matches(request.password(), saved.getPassword()))
				.orElseThrow(() -> new BadCredentialsException("Invalid email or password"));
		return buildAuthResponse(user);
	}

	private AuthResponse buildAuthResponse(User user) {
		String accessToken = jwtService.generateToken(user);
		return new AuthResponse(accessToken, "Bearer", jwtService.getExpirationSeconds(),
				UserResponse.from(user));
	}

}
