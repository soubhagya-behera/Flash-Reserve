package com.soubhagya.flashreserve.service;

import java.time.Duration;
import java.util.UUID;

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
 * Distributed per-user request-rate protection built on Redisson's atomic
 * rate limiter ({@link RateType#OVERALL}: one shared bucket per user across
 * every application instance).
 *
 * Responsibility is intentionally narrow: derive the rate-limit key from the
 * stable authenticated identity, enforce the configured policy, and translate
 * failures into domain-level errors. It performs no database access, holds no
 * seat locks, and knows no business rules.
 *
 * Policy lifecycle: {@code trySetRate} configures a user's bucket on first
 * use; the idle TTL below retires abandoned buckets so persisted policies
 * converge to the current configuration without operator intervention.
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

	private final RedissonClient redisson;

	private final ReservationProperties.RateLimit policy;

	public RateLimitService(RedissonClient redisson, ReservationProperties reservationProperties) {
		this.redisson = redisson;
		this.policy = reservationProperties.rateLimit();
	}

	public void checkReservationLimit(UUID userId) {
		check(SCOPE_RESERVATION, userId);
	}

	static String limiterKey(String scope, UUID userId) {
		return KEY_PREFIX + scope + ":user:" + userId;
	}

	private void check(String scope, UUID userId) {
		String key = limiterKey(scope, userId);
		try {
			RRateLimiter limiter = redisson.getRateLimiter(key);
			limiter.trySetRate(RateType.OVERALL, policy.capacity(), policy.refillPeriod());
			if (!limiter.tryAcquire()) {
				throw new RateLimitExceededException(policy.refillPeriod());
			}
			limiter.expire(idleTtl());
		}
		catch (RedisException ex) {
			log.warn("Distributed rate limiter unavailable for {}: {}", key, ex.getMessage());
			throw new ServiceUnavailableException("Reservation temporarily unavailable. Please retry.");
		}
	}

	private Duration idleTtl() {
		Duration ttl = policy.refillPeriod().multipliedBy(100);
		return ttl.toMinutes() < 1 ? Duration.ofMinutes(1) : ttl;
	}

}
