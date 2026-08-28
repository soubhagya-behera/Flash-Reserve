package com.soubhagya.flashreserve.service;

import java.time.Duration;
import java.util.UUID;

import com.soubhagya.flashreserve.config.AuthProperties;
import com.soubhagya.flashreserve.config.ReservationProperties;
import com.soubhagya.flashreserve.exception.RateLimitExceededException;
import com.soubhagya.flashreserve.exception.ServiceUnavailableException;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Distributed request-rate protection built on Redisson's atomic rate limiter
 * ({@link RateType#OVERALL}: one shared bucket per subject across every
 * application instance). This is the single rate-limiting component of the
 * application: authenticated endpoints are keyed by the stable user id,
 * public endpoints (authentication) by the client IP address.
 *
 * Responsibility is intentionally narrow: derive the rate-limit key from the
 * stable caller identity, enforce the configured policy, and translate
 * failures into domain-level errors. It performs no database access, holds no
 * seat locks, and knows no business rules.
 *
 * Policy lifecycle: {@code trySetRate} configures a bucket on first use; the
 * idle TTL below retires abandoned buckets so persisted policies converge to
 * the current configuration without operator intervention.
 *
 * Redis failure is fail-closed: a request is never processed when its rate
 * limit could not actually be checked. Limiter keys live in their own
 * {@code flashreserve:ratelimit:*} namespace and never collide with the
 * reservation seat locks.
 */
@Slf4j
@Service
public class RateLimitService {

	static final String KEY_PREFIX = "flashreserve:ratelimit:";

	static final String SCOPE_RESERVATION = "reservation";

	static final String SCOPE_LOGIN = "login";

	static final String SCOPE_REGISTRATION = "registration";

	private static final String RESERVATION_REJECTION_MESSAGE =
			"Too many reservation requests. Please try again shortly.";

	private static final String AUTH_REJECTION_MESSAGE =
			"Too many authentication attempts. Please try again shortly.";

	private final RedissonClient redisson;

	private final ReservationProperties.RateLimit reservationPolicy;

	private final AuthProperties authProperties;

	public RateLimitService(RedissonClient redisson,
			ReservationProperties reservationProperties, AuthProperties authProperties) {
		this.redisson = redisson;
		this.reservationPolicy = reservationProperties.rateLimit();
		this.authProperties = authProperties;
	}

	public void checkReservationLimit(UUID userId) {
		check(SCOPE_RESERVATION, "user:" + userId,
				reservationPolicy.capacity(), reservationPolicy.refillPeriod(),
				RESERVATION_REJECTION_MESSAGE);
	}

	public void checkLoginLimit(String clientIp) {
		check(SCOPE_LOGIN, ipSubject(clientIp),
				authProperties.login().capacity(), authProperties.login().refillPeriod(),
				AUTH_REJECTION_MESSAGE);
	}

	public void checkRegistrationLimit(String clientIp) {
		check(SCOPE_REGISTRATION, ipSubject(clientIp),
				authProperties.registration().capacity(), authProperties.registration().refillPeriod(),
				AUTH_REJECTION_MESSAGE);
	}

	static String limiterKey(String scope, String subject) {
		return KEY_PREFIX + scope + ":" + subject;
	}

	private static String ipSubject(String clientIp) {
		return "ip:" + clientIp;
	}

	private void check(String scope, String subject, int capacity, Duration refillPeriod,
			String rejectionMessage) {
		String key = limiterKey(scope, subject);
		try {
			RRateLimiter limiter = redisson.getRateLimiter(key);
			limiter.trySetRate(RateType.OVERALL, capacity, refillPeriod);
			if (!limiter.tryAcquire()) {
				throw new RateLimitExceededException(refillPeriod, rejectionMessage);
			}
			limiter.expire(idleTtl(refillPeriod));
		}
		catch (RedisException ex) {
			log.warn("Distributed rate limiter unavailable for {}: {}", key, ex.getMessage());
			throw new ServiceUnavailableException("Service temporarily unavailable. Please retry.");
		}
	}

	private Duration idleTtl(Duration refillPeriod) {
		Duration ttl = refillPeriod.multipliedBy(100);
		return ttl.toMinutes() < 1 ? Duration.ofMinutes(1) : ttl;
	}

}
