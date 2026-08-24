package com.soubhagya.flashreserve.security;

import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.UserRole;

public record UserPrincipal(UUID id, String email, UserRole role) {

	public String authority() {
		return "ROLE_" + role.name();
	}

}
