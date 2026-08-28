package com.soubhagya.flashreserve.exception;

import java.time.Duration;

/**
 * Thrown when a caller exceeds the configured distributed request-rate policy.
 * Carries a conservative Retry-After hint: one full refill period is the point
 * at which this caller's own quota is guaranteed to be restored.
 */
public class RateLimitExceededException extends RuntimeException {

	private final long retryAfterSeconds;

	public RateLimitExceededException(Duration refillPeriod, String message) {
		super(message);
		this.retryAfterSeconds = Math.max(1, refillPeriod.toSeconds());
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
