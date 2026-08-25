package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.soubhagya.flashreserve.dto.booking.ReservationResponse;
import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.exception.InvalidStateTransitionException;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.service.BookingService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.orm.ObjectOptimisticLockingFailureException;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"reservation.hold-duration=15m"
})
class ReservationConcurrencyTests {

	private static final int THREADS = 8;

	@Autowired
	private BookingService bookingService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private SeatRepository seatRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private final List<UUID> createdUserIds = new ArrayList<>();

	private final List<UUID> createdEventIds = new ArrayList<>();

	@AfterEach
	void cleanDatabase() {
		for (UUID eventId : createdEventIds) {
			bookingRepository.findByEventId(eventId).forEach(bookingRepository::delete);
			seatRepository.findByEventId(eventId).forEach(seatRepository::delete);
			eventRepository.deleteById(eventId);
		}
		createdEventIds.clear();
		createdUserIds.forEach(userRepository::deleteById);
		createdUserIds.clear();
	}

	private Event newPublishedEvent(String name, int seatCount) {
		Event published = new Event(name, "d", "Hall", Instant.now().plusSeconds(86_400), seatCount);
		published.setStatus(EventStatus.PUBLISHED);
		Event saved = eventRepository.save(published);
		createdEventIds.add(saved.getId());
		List<Seat> seats = new ArrayList<>();
		for (int i = 1; i <= seatCount; i++) {
			seats.add(new Seat(saved, String.format("S%03d", i)));
		}
		seatRepository.saveAll(seats);
		return saved;
	}

	private List<User> newUsers(int count) {
		List<User> users = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			User user = userRepository.save(new User("Racer " + i, "racer" + System.nanoTime() + "-" + i + "@example.test",
					passwordEncoder.encode("password-123"), UserRole.USER));
			users.add(user);
			createdUserIds.add(user.getId());
		}
		return users;
	}

	@Test
	void onlyOneConcurrentReservationWinsForTheSameSeat() throws Exception {
		Event event = newPublishedEvent("Concurrency Event", 1);
		Seat seat = seatRepository.findByEventIdAndStatus(event.getId(), SeatStatus.AVAILABLE).get(0);
		List<User> users = newUsers(THREADS);

		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<Object>> results = new ArrayList<>();
		for (User user : users) {
			results.add(pool.submit((Callable<Object>) () -> {
				startGate.await();
				try {
					return bookingService.reserve(user.getId(), event.getId(), seat.getId());
				}
				catch (Throwable failure) {
					return failure;
				}
			}));
		}
		startGate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

		int successes = 0;
		int conflicts = 0;
		List<Object> unexpected = new ArrayList<>();
		for (Future<Object> future : results) {
			Object outcome = future.get();
			if (outcome instanceof ReservationResponse) {
				successes++;
			}
			else if (outcome instanceof InvalidStateTransitionException
					|| outcome instanceof ObjectOptimisticLockingFailureException) {
				conflicts++;
			}
			else {
				unexpected.add(outcome);
			}
		}

		assertThat(unexpected).as("no unexpected failures allowed").isEmpty();
		assertThat(successes).as("exactly one winner for a single AVAILABLE seat").isEqualTo(1);
		assertThat(conflicts).as("all losers must receive a conflict").isEqualTo(THREADS - 1);

		Seat reloaded = seatRepository.findById(seat.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(SeatStatus.HELD);

		long pendingBookings = bookingRepository.findBySeatId(seat.getId()).stream()
				.filter(booking -> booking.getStatus() == BookingStatus.PENDING)
				.count();
		assertThat(pendingBookings).as("exactly one active reservation - no oversell").isEqualTo(1);
	}

	@Test
	void differentSeatsAreReservedConcurrentlyWithoutSerializingTheEvent() throws Exception {
		Event event = newPublishedEvent("Parallel Seats Event", THREADS);
		List<Seat> seats = seatRepository.findByEventIdAndStatus(event.getId(), SeatStatus.AVAILABLE);
		List<User> users = newUsers(THREADS);

		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<Object>> results = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			User user = users.get(i);
			Seat target = seats.get(i);
			results.add(pool.submit((Callable<Object>) () -> {
				startGate.await();
				try {
					return bookingService.reserve(user.getId(), event.getId(), target.getId());
				}
				catch (Throwable failure) {
					return failure;
				}
			}));
		}
		startGate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

		int successes = 0;
		List<Object> failures = new ArrayList<>();
		for (Future<Object> future : results) {
			Object outcome = future.get();
			if (outcome instanceof ReservationResponse) {
				successes++;
			}
			else {
				failures.add(outcome);
			}
		}

		assertThat(failures).as("distinct seats must never contend on one lock").isEmpty();
		assertThat(successes).as("every distinct seat is reservable concurrently").isEqualTo(THREADS);

		assertThat(seatRepository.findByEventIdAndStatus(event.getId(), SeatStatus.HELD))
				.as("all seats end HELD, each via its own lock")
				.hasSize(THREADS);
		long pendingBookings = bookingRepository.findByEventId(event.getId()).stream()
				.filter(booking -> booking.getStatus() == BookingStatus.PENDING)
				.count();
		assertThat(pendingBookings).isEqualTo(THREADS);
	}

}
