package com.soubhagya.flashreserve.dto.auth;

import java.util.UUID;

import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;

public record UserResponse(UUID id, String name, String email, UserRole role) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole());
	}

}
