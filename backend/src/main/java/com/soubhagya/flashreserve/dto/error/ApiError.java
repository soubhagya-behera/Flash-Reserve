package com.soubhagya.flashreserve.dto.error;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.http.HttpStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String error, String message, String path,
		Map<String, String> fieldErrors) {

	public static ApiError of(HttpStatus status, String message, String path) {
		return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, null);
	}

	public static ApiError of(HttpStatus status, String message, String path, Map<String, String> fieldErrors) {
		return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, fieldErrors);
	}

}
