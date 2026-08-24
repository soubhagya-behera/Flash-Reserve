package com.soubhagya.flashreserve;

import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.exception.ApiExceptionHandler;
import com.soubhagya.flashreserve.exception.DuplicateEmailException;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;

import org.junit.jupiter.api.Test;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;

import org.springframework.security.authentication.BadCredentialsException;

import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTests {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/example");

	@Test
	void resourceNotFoundMapsTo404WithSafeBody() {
		ResponseEntity<ApiError> response =
				handler.handleResourceNotFound(new ResourceNotFoundException("Event not found: 42"), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(response.getBody().message()).isEqualTo("Event not found: 42");
		assertThat(response.getBody().path()).isEqualTo("/api/example");
		assertThat(response.getBody().timestamp()).isNotNull();
	}

	@Test
	void duplicateEmailMapsTo409() {
		ResponseEntity<ApiError> response =
				handler.handleDuplicateEmail(new DuplicateEmailException("Email already registered"), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody().message()).isEqualTo("Email already registered");
	}

	@Test
	void badCredentialsMapTo401WithGenericMessage() {
		ResponseEntity<ApiError> response =
				handler.handleBadCredentials(new BadCredentialsException("Invalid email or password"), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().message()).isEqualTo("Invalid email or password");
		assertThat(response.getBody().fieldErrors()).isNull();
	}

	@Test
	void validationFailuresMapTo400WithFieldErrorsOnly() throws Exception {
		BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "registerRequest");
		bindingResult.addError(new FieldError("registerRequest", "email", "Email must be a valid address"));
		MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
				new MethodParameter(getClass().getDeclaredMethods()[0], -1), bindingResult);

		ResponseEntity<ApiError> response = handler.handleValidation(ex, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().fieldErrors()).containsEntry("email", "Email must be a valid address");
		assertThat(response.getBody().message()).isEqualTo("Validation failed");
	}

	@Test
	void unreadableBodyMapsTo400WithoutInternalDetails() {
		HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
				"JSON parse error: unexpected token", new MockHttpInputMessage(new byte[0]));

		ResponseEntity<ApiError> response = handler.handleUnreadableBody(ex, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().message()).isEqualTo("Malformed request body");
		assertThat(response.getBody().message()).doesNotContain("JSON parse error");
	}

	@Test
	void unexpectedExceptionsAreSanitizedForClients() {
		ResponseEntity<ApiError> response =
				handler.handleUnexpected(new RuntimeException("db connection string secret-stuff"), request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody().message())
				.isEqualTo("Unexpected internal error")
				.doesNotContain("secret");
		assertThat(response.getBody().fieldErrors()).isNull();
	}

}
