package com.soubhagya.flashreserve.service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.soubhagya.flashreserve.config.ReservationProperties;
import com.soubhagya.flashreserve.exception.InvalidStateTransitionException;
import com.soubhagya.flashreserve.exception.ServiceUnavailableException;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Distributed coordination for the reservation hot path.
 *
 * Responsibility is intentionally narrow: derive the deterministic lock key
 * for a seat, acquire the lock with a bounded wait, run the protected action,
 * and release the lock safely. It owns no business rules.
 *
 * The key format is part of the cross-instance contract and must stay stable,
 * so every instance in a cluster competes for the same per-seat lock.
 */
@Slf4j
@Service
public class ReservationLockService {

	static final String LOCK_KEY_PREFIX = "flashreserve:reservation:event:";

	private final RedissonClient redisson;

	private final Duration lockWaitDuration;

	public ReservationLockService(RedissonClient redisson, ReservationProperties reservationProperties) {
		this.redisson = redisson;
		this.lockWaitDuration = reservationProperties.lockWaitDuration();
	}

	/**
	 * Deterministic identity of the protected resource (one specific seat of
	 * one specific event). Never derived from userId: two different users
	 * reserving the same seat must contend for this exact lock, while
	 * different seats never block each other.
	 */
	public static String lockKey(UUID eventId, UUID seatId) {
		return LOCK_KEY_PREFIX + eventId + ":seat:" + seatId;
	}

	/**
	 * Runs the protected action while holding the distributed lock for the
	 * given seat. Fails fast with a domain-level 409 when another request
	 * currently owns the lock, and with a controlled 503 when Redis itself is
	 * unreachable - correctness is never claimed from a lock that was not
	 * actually acquired. The database transaction and optimistic locking stay
	 * fully responsible for final consistency.
	 */
	public <T> T withSeatLock(UUID eventId, UUID seatId, Supplier<T> protectedAction) {
		String key = lockKey(eventId, seatId);
		RLock lock = redisson.getLock(key);
		boolean acquired;
		try {
			acquired = lock.tryLock(lockWaitDuration.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new ServiceUnavailableException("Reservation temporarily unavailable. Please retry.");
		}
		catch (RedisException ex) {
			log.warn("Distributed lock unavailable for {}: {}", key, ex.getMessage());
			throw new ServiceUnavailableException("Reservation temporarily unavailable. Please retry.");
		}
		if (!acquired) {
			throw new InvalidStateTransitionException("Seat is currently being processed.");
		}
		try {
			return protectedAction.get();
		}
		finally {
			try {
				if (lock.isHeldByCurrentThread()) {
					lock.unlock();
				}
			}
			catch (RedisException ex) {
				log.warn("Could not release distributed lock {}; it will expire via its lease instead", key);
			}
		}
	}

}
