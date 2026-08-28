package com.soubhagya.flashreserve;

import java.time.Duration;
import java.util.UUID;

import com.soubhagya.flashreserve.config.ReservationProperties;
import com.soubhagya.flashreserve.exception.InvalidStateTransitionException;
import com.soubhagya.flashreserve.exception.ServiceUnavailableException;
import com.soubhagya.flashreserve.service.ReservationLockService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReservationLockServiceTests {

	private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID SEAT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private RedissonClient redisson;

	private RLock lock;

	private ReservationLockService lockService;

	@BeforeEach
	void setUp() {
		redisson = Mockito.mock(RedissonClient.class);
		lock = Mockito.mock(RLock.class);
		lockService = new ReservationLockService(redisson, new ReservationProperties(Duration.ofMinutes(5),
				Duration.ofSeconds(30), Duration.ofSeconds(2),
				new ReservationProperties.RateLimit(10, Duration.ofSeconds(1))));
	}

	@Test
	void lockKeyIsDeterministicForSameEventAndSeat() {
		assertThat(ReservationLockService.lockKey(EVENT_ID, SEAT_ID))
				.isEqualTo("flashreserve:reservation:event:" + EVENT_ID + ":seat:" + SEAT_ID);
		assertThat(ReservationLockService.lockKey(EVENT_ID, SEAT_ID))
				.isEqualTo(ReservationLockService.lockKey(EVENT_ID, SEAT_ID));
	}

	@Test
	void differentSeatsMapToDifferentLocksAndDoNotBlockEachOther() {
		UUID otherSeat = UUID.randomUUID();

		String first = ReservationLockService.lockKey(EVENT_ID, SEAT_ID);
		String second = ReservationLockService.lockKey(EVENT_ID, otherSeat);
		String otherEvent = ReservationLockService.lockKey(UUID.randomUUID(), SEAT_ID);

		assertThat(second).isNotEqualTo(first);
		assertThat(otherEvent).isNotEqualTo(first);
	}

	@Test
	void acquiredLockRunsActionAndIsReleased() throws Exception {
		when(redisson.getLock(anyString())).thenReturn(lock);
		when(lock.tryLock(anyLong(), any())).thenReturn(true);
		when(lock.isHeldByCurrentThread()).thenReturn(true);

		String outcome = lockService.withSeatLock(EVENT_ID, SEAT_ID, () -> "reserved");

		assertThat(outcome).isEqualTo("reserved");
		verify(lock).tryLock(eq(2000L), any());
		verify(lock).unlock();
	}

	@Test
	void contendedLockFailsFastWithConflictWithoutRunningTheAction() throws Exception {
		when(redisson.getLock(anyString())).thenReturn(lock);
		when(lock.tryLock(anyLong(), any())).thenReturn(false);

		assertThatThrownBy(() -> lockService.withSeatLock(EVENT_ID, SEAT_ID, () -> "reserved"))
				.isInstanceOf(InvalidStateTransitionException.class)
				.hasMessage("Seat is currently being processed.");

		verify(lock, never()).unlock();
	}

	@Test
	void unreachableRedisYieldsControlledServiceUnavailableFailure() throws Exception {
		when(redisson.getLock(anyString())).thenReturn(lock);
		when(lock.tryLock(anyLong(), any())).thenThrow(new RedisException("Connection refused"));

		assertThatThrownBy(() -> lockService.withSeatLock(EVENT_ID, SEAT_ID, () -> "reserved"))
				.isInstanceOf(ServiceUnavailableException.class)
				.hasMessageNotContaining("Redis")
				.hasMessageNotContaining("Redisson");

		verify(lock, never()).unlock();
	}

	@Test
	void interruptedWaitRestoresTheInterruptFlagAndFailsControlled() throws Exception {
		when(redisson.getLock(anyString())).thenReturn(lock);
		when(lock.tryLock(anyLong(), any())).thenThrow(new InterruptedException());

		assertThatThrownBy(() -> lockService.withSeatLock(EVENT_ID, SEAT_ID, () -> "reserved"))
				.isInstanceOf(ServiceUnavailableException.class);

		assertThat(Thread.interrupted()).as("interrupt flag must be restored for the caller").isTrue();
		verify(lock, never()).unlock();
	}

	@Test
	void failedUnlockNeverMasksASuccessfulReservationOutcome() throws Exception {
		when(redisson.getLock(anyString())).thenReturn(lock);
		when(lock.tryLock(anyLong(), any())).thenReturn(true);
		when(lock.isHeldByCurrentThread()).thenReturn(true);
		Mockito.doThrow(new RedisException("node went away")).when(lock).unlock();

		String outcome = lockService.withSeatLock(EVENT_ID, SEAT_ID, () -> "reserved");

		assertThat(outcome).isEqualTo("reserved");
	}

}
