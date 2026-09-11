package com.soubhagya.flashreserve.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(

		@NotBlank(message = "Email is required")
		@Email(message = "Email must be a valid address")
		@Size(max = 255, message = "Email must not exceed 255 characters")
		String email,

		@NotBlank(message = "Verification code is required")
		@Pattern(regexp = "^[0-9]{6}$", message = "Verification code must be 6 digits")
		String code) {
}
