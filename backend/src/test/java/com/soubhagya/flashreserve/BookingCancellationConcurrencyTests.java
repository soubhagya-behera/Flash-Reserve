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

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.service.BookingService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that a user cancellation and the background hold expiration racing
 * on the same due booking always leave a consistent pair: the booking ends
 * CANCELLED or EXPIRED and the seat ends AVAILABLE. The Seat
 * {@code @Version} optimistic lock arbitrates the single seat write; the
 * loser rolls back. Runs without a test transaction because both operations
 * commit in their own database transactions, exactly as in production.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m"
})
class BookingCancellationConcurrencyTests {

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

	private User newUser(String email) {
		User user = userRepository.save(new User("User", email,
				passwordEncoder.encode("password-123"), UserRole.USER));
		createdUserIds.add(user.getId());
		return user;
	}

	private Event newPublishedEvent() {
		Event event = new Event("Race Event", "d", "Hall", Instant.now().plusSeconds(86_400), 3);
		event.setStatus(EventStatus.PUBLISHED);
		Event saved = eventRepository.save(event);
		createdEventIds.add(saved.getId());
		List<Seat> seats = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			seats.add(new Seat(saved, String.format("S%03d", i)));
		}
		seatRepository.saveAll(seats);
		return saved;
	}

	@Test
	void cancellationAndExpirationRaceLeavesConsistentDatabaseState() throws Exception {
		User user = newUser("cancel-expire-race@example.test");
		Event event = newPublishedEvent();
		Seat seat = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001").orElseThrow();

		bookingService.reserve(user.getId(), event.getId(), seat.getId());
		Booking booking = bookingRepository.findBySeatId(seat.getId()).stream().findFirst().orElseThrow();
		booking.setExpiresAt(Instant.now().minusSeconds(1));
		bookingRepository.saveAndFlush(booking);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<Object>> outcomes = new ArrayList<>();
		outcomes.add(pool.submit((Callable<Object>) () -> {
			startGate.await();
			try {
				bookingService.cancelBooking(booking.getId(), user.getId());
				return "cancelled";
			}
			catch (Throwable failure) {
				return failure;
			}
		}));
		outcomes.add(pool.submit((Callable<Object>) () -> {
			startGate.await();
			try {
				bookingService.expireIfDue(booking.getId());
				return "expired";
			}
			catch (Throwable failure) {
				return failure;
			}
		}));
		startGate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

		Booking reloaded = bookingRepository.findById(booking.getId()).orElseThrow();
		Seat reloadedSeat = seatRepository.findById(seat.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isIn(BookingStatus.CANCELLED, BookingStatus.EXPIRED);
		assertThat(reloadedSeat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
		assertThat(bookingRepository.findBySeatId(seat.getId()))
				.as("exactly one booking row for the raced seat")
				.hasSize(1);
	}

}