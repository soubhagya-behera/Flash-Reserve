package com.soubhagya.flashreserve.service;

import com.soubhagya.flashreserve.dto.auth.AuthResponse;
import com.soubhagya.flashreserve.dto.auth.LoginRequest;
import com.soubhagya.flashreserve.dto.auth.RegisterRequest;
import com.soubhagya.flashreserve.dto.auth.UserResponse;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.exception.DuplicateEmailException;
import com.soubhagya.flashreserve.security.JwtService;

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

	public AuthResponse register(RegisterRequest request) {
		if (userService.existsByEmail(request.email())) {
			throw new DuplicateEmailException("Email already registered");
		}
		String encodedPassword = passwordEncoder.encode(request.password());
		User user = userService.createUser(request.name(), request.email(), encodedPassword, UserRole.USER);
		return buildAuthResponse(user);
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
