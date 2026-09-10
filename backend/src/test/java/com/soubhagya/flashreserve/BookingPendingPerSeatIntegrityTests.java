package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.persistence.EntityManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SEC-03 backstop proof for the reservation hot path. */
@SpringBootTest
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m"
})
class BookingPendingPerSeatIntegrityTests {
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
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private TransactionTemplate transactionTemplate;
	@Autowired
	private EntityManager entityManager;
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
		Event event = new Event("Integrity Event", "d", "Hall", Instant.now().plusSeconds(86_400), 2);
		event.setStatus(EventStatus.PUBLISHED);
		Event saved = eventRepository.save(event);
		createdEventIds.add(saved.getId());
		List<Seat> seats = new ArrayList<>();
		for (int i = 1; i <= 2; i++) {
			seats.add(new Seat(saved, String.format("S%03d", i)));
		}
		seatRepository.saveAll(seats);
		return saved;
	}
	private Seat seat(Event event, String seatNumber) {
		return seatRepository.findByEventIdAndSeatNumber(event.getId(), seatNumber).orElseThrow();
	}
	@Test
	void partialUniqueIndexExistsWithPendingPredicate() {
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"select indexname, indexdef from pg_indexes where indexname = 'uk_bookings_one_pending_per_seat'");
		assertThat(rows).hasSize(1);
		String definition = String.valueOf(rows.get(0).get("indexdef"));
		assertThat(definition).contains("seat_id");
		assertThat(definition).contains("PENDING");
	}
	@Test
	void postgresRejectsSecondPending() {
		User first = newUser("sec03-first@example.test");
		User second = newUser("sec03-second@example.test");
		Event event = newPublishedEvent();
		Seat seat = seat(event, "S001");
		Instant expiry = Instant.now().plusSeconds(900);
		UUID firstId = first.getId();
		UUID secondId = second.getId();
		UUID eventId = event.getId();
		UUID seatId = seat.getId();
		transactionTemplate.execute(status -> bookingRepository.saveAndFlush(new Booking(
				userRepository.getReferenceById(firstId),
				eventRepository.getReferenceById(eventId),
				seatRepository.getReferenceById(seatId), expiry)));
		entityManager.clear();
		assertThatThrownBy(() -> transactionTemplate.execute(status -> bookingRepository.saveAndFlush(new Booking(
				userRepository.getReferenceById(secondId),
				eventRepository.getReferenceById(eventId),
				seatRepository.getReferenceById(seatId), expiry))))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasStackTraceContaining("uk_bookings_one_pending_per_seat");
	}

	@Test
	void reservationLoserSeesSeatConflict() {
		User first = newUser("sec03-winner@example.test");
		User second = newUser("sec03-loser@example.test");
		Event event = newPublishedEvent();
		Seat seat = seat(event, "S001");
		bookingService.reserve(first.getId(), event.getId(), seat.getId());
		entityManager.clear();
		assertThatThrownBy(() -> bookingService.reserve(second.getId(), event.getId(), seat.getId()))
				.isInstanceOf(InvalidStateTransitionException.class)
				.hasMessage("Seat is no longer available.");
		entityManager.clear();
		assertThat(bookingRepository.findBySeatId(seat.getId())).hasSize(1);
	}
	@Test
	void settledHistoryNeverBlocksFreshReservation() {
		User first = newUser("sec03-old@example.test");
		User second = newUser("sec03-new@example.test");
		Event event = newPublishedEvent();
		Seat seat = seat(event, "S001");
		bookingService.reserve(first.getId(), event.getId(), seat.getId());
		Booking booking = bookingRepository.findBySeatId(seat.getId()).stream().findFirst().orElseThrow();
		booking.setStatus(BookingStatus.CANCELLED);
		bookingRepository.saveAndFlush(booking);
		entityManager.clear();
		Seat released = seatRepository.findById(seat.getId()).orElseThrow();
		released.setStatus(SeatStatus.AVAILABLE);
		seatRepository.saveAndFlush(released);
		entityManager.clear();
		var reservation = bookingService.reserve(second.getId(), event.getId(), seat.getId());
		assertThat(reservation.status()).isEqualTo(BookingStatus.PENDING);
		assertThat(bookingRepository.findBySeatId(seat.getId())).hasSize(2);
	}
}
