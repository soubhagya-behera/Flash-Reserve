package com.soubhagya.flashreserve;

import java.time.Duration;
import java.util.UUID;

import com.soubhagya.flashreserve.config.ReservationProperties;
import com.soubhagya.flashreserve.exception.RateLimitExceededException;
import com.soubhagya.flashreserve.exception.ServiceUnavailableException;
import com.soubhagya.flashreserve.service.RateLimitService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitServiceTests {

	private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private RedissonClient redisson;

	private RRateLimiter limiter;

	private RateLimitService rateLimitService;

	@BeforeEach
	void setUp() {
		redisson = mock(RedissonClient.class);
		limiter = mock(RRateLimiter.class);
		when(redisson.getRateLimiter(anyString())).thenReturn(limiter);
		rateLimitService = new RateLimitService(redisson, new ReservationProperties(
				Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(2),
				new ReservationProperties.RateLimit(3, Duration.ofSeconds(5))));
	}

	@Test
	void firstUseAppliesTheConfiguredPolicyToTheUserBucket() {
		when(limiter.tryAcquire()).thenReturn(true);

		rateLimitService.checkReservationLimit(USER_ID);

		verify(limiter).trySetRate(RateType.OVERALL, 3L, Duration.ofSeconds(5));
	}

	@Test
	void requestUnderLimitIsAllowedAndBucketKeepsALiveTtl() {
		when(limiter.tryAcquire()).thenReturn(true);
		when(limiter.trySetRate(any(), anyLong(), any())).thenReturn(true);

		assertThatCode(() -> rateLimitService.checkReservationLimit(USER_ID))
				.doesNotThrowAnyException();

		verify(limiter).expire(Duration.ofSeconds(500));
	}

	@Test
	void requestOverLimitIsRejectedWithSafeMessageAndRetryHint() {
		when(limiter.tryAcquire()).thenReturn(false);

		assertThatThrownBy(() -> rateLimitService.checkReservationLimit(USER_ID))
				.isInstanceOfSatisfying(RateLimitExceededException.class, ex -> {
					assertThat(ex.retryAfterSeconds()).isEqualTo(5);
					assertThat(ex.getMessage())
							.isEqualTo("Too many reservation requests. Please try again shortly.")
							.doesNotContain("Redis")
							.doesNotContain("token");
				});
	}

	@Test
	void rejectedRequestNeverTouchesBucketLifecycleCommands() {
		when(limiter.tryAcquire()).thenReturn(false);

		assertThatThrownBy(() -> rateLimitService.checkReservationLimit(USER_ID))
				.isInstanceOf(RateLimitExceededException.class);

		verify(limiter, never()).expire(any(Duration.class));
	}

	@Test
	void limitKeyUsesDedicatedNamespaceAndStableUserIdentity() {
		when(limiter.tryAcquire()).thenReturn(true);

		rateLimitService.checkReservationLimit(USER_ID);

		verify(redisson).getRateLimiter(
				"flashreserve:ratelimit:reservation:user:" + USER_ID);
	}

	@Test
	void differentUsersUseIndependentBuckets() {
		UUID otherUser = UUID.randomUUID();
		when(limiter.tryAcquire()).thenReturn(true);

		rateLimitService.checkReservationLimit(USER_ID);
		rateLimitService.checkReservationLimit(otherUser);

		verify(redisson).getRateLimiter("flashreserve:ratelimit:reservation:user:" + USER_ID);
		verify(redisson).getRateLimiter("flashreserve:ratelimit:reservation:user:" + otherUser);
	}

	@Test
	void unavailableRedisFailsClosedInsteadOfBypassingTheLimit() {
		when(redisson.getRateLimiter(anyString()))
				.thenThrow(new RedisException("Connection refused"));

		assertThatThrownBy(() -> rateLimitService.checkReservationLimit(USER_ID))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessageNotContaining("Redis")
				.hasMessageNotContaining("Redisson");
	}

}
