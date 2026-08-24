package com.soubhagya.flashreserve.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

		@NotBlank(message = "Name is required")
		@Size(max = 100, message = "Name must not exceed 100 characters")
		String name,

		@NotBlank(message = "Email is required")
		@Email(message = "Email must be a valid address")
		@Size(max = 255, message = "Email must not exceed 255 characters")
		String email,

		@NotBlank(message = "Password is required")
		@Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
		String password) {

}
