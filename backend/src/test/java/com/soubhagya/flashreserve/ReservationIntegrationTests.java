package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import com.soubhagya.flashreserve.security.JwtService;
import com.soubhagya.flashreserve.service.BookingService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m"
})
class ReservationIntegrationTests {

	private static final Instant TEST_START = Instant.now();

	@Autowired
	private MockMvc mockMvc;

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
	private JwtService jwtService;

	@Autowired
	private BookingService bookingService;

	private User newUser(String email) {
		return userRepository.save(new User("User", email,
				passwordEncoder.encode("password-123"), UserRole.USER));
	}

	private String tokenFor(User user) {
		return jwtService.generateToken(user);
	}

	private Event newEvent(EventStatus status) {
		Event event = new Event("Test Event", "desc", "Venue", Instant.now().plusSeconds(86_400), 3);
		event.setStatus(status == null ? EventStatus.PUBLISHED : status);
		event = eventRepository.save(event);
		List<Seat> seats = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			seats.add(new Seat(event, String.format("S%03d", i)));
		}
		seatRepository.saveAll(seats);
		return event;
	}

	private String reserveUrl(UUID eventId, UUID seatId) {
		return "/api/events/" + eventId + "/seats/" + seatId + "/reservations";
	}

	private Event publishedEvent() {
		return newEvent(EventStatus.PUBLISHED);
	}

	@Test
	void authenticatedUserCanReserveAvailableSeat() throws Exception {
		User user = newUser("reserver@example.test");
		Event event = publishedEvent();
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001").orElseThrow().getId();

		mockMvc.perform(post(reserveUrl(event.getId(), seatId))
						.header("Authorization", "Bearer " + tokenFor(user)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.bookingId").isNotEmpty())
				.andExpect(jsonPath("$.eventId").value(event.getId().toString()))
				.andExpect(jsonPath("$.seatId").value(seatId.toString()))
				.andExpect(jsonPath("$.seatNumber").value("S001"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andExpect(jsonPath("$.createdAt").isNotEmpty());
	}

	@Test
	void anonymousReservationIsRejected() throws Exception {
		Event event = publishedEvent();
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001").orElseThrow().getId();

		mockMvc.perform(post(reserveUrl(event.getId(), seatId)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Authentication required"));
	}

	@Test
	void adminCannotReserveSeats() throws Exception {
		User admin = userRepository.save(new User("Admin", "reserving-admin@example.test",
				passwordEncoder.encode("password-123"), UserRole.ADMIN));
		Event event = publishedEvent();
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001").orElseThrow().getId();

		mockMvc.perform(post(reserveUrl(event.getId(), seatId))
						.header("Authorization", "Bearer " + tokenFor(admin)))
				.andExpect(status().isForbidden());

		assertThat(seatRepository.findById(seatId).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
	}

	@Test
	void draftEventCannotBeReservedWithoutRevealingExistence() throws Exception {
		User user = newUser("draft-user@example.test");
		Event draft = newEvent(EventStatus.DRAFT);
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(draft.getId(), "S001").orElseThrow().getId();

		mockMvc.perform(post(reserveUrl(draft.getId(), seatId))
						.header("Authorization", "Bearer " + tokenFor(user)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Event not found: " + draft.getId()));
	}

	@Test
	void cancelledEventCannotBeReserved() throws Exception {
		User user = newUser("cancelled-user@example.test");
		Event cancelled = newEvent(EventStatus.CANCELLED);
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(cancelled.getId(), "S001").orElseThrow().getId();

		mockMvc.perform(post(reserveUrl(cancelled.getId(), seatId))
						.header("Authorization", "Bearer " + tokenFor(user)))
				.andExpect(status().isNotFound());
	}

	@Test
	void nonexistentEventReturns404() throws Exception {
		User user = newUser("ghost-event-user@example.test");
		UUID unknownEvent = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
		UUID unknownSeat = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

		mockMvc.perform(post(reserveUrl(unknownEvent, unknownSeat))
						.header("Authorization", "Bearer " + tokenFor(user)))
				.andExpect(status().isNotFound());
	}

	@Test
	void nonexistentSeatReturns404() throws Exception {
		User user = newUser("ghost-seat-user@example.test");
		Event event = publishedEvent();
		UUID unknownSeat = UUID.fromString("00000000-0000-0000-0000-0000000000e3");

		mockMvc.perform(post(reserveUrl(event.getId(), unknownSeat))
						.header("Authorization", "Bearer " + tokenFor(user)))
				.andExpect(status().isNotFound());
	}

	@Test
	void seatFromAnotherEventIsRejectedSafely() throws Exception {
		User user = newUser("cross-event-user@example.test");
		Event eventA = publishedEvent();
		Event eventB = publishedEvent();
		UUID seatOfB = seatRepository.findByEventIdAndSeatNumber(eventB.getId(), "S002").orElseThrow().getId();

		mockMvc.perform(post(reserveUrl(eventA.getId(), seatOfB))
						.header("Authorization", "Bearer " + tokenFor(user)))
				.andExpect(status().isNotFound());

		assertThat(seatRepository.findById(seatOfB).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
	}

	@Test
	void successfulReservationHoldsSeatAndCreatesPendingBookingWithExpiry() throws Exception {
		User user = newUser("happy-path@example.test");
		Event event = publishedEvent();
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001").orElseThrow().getId();

		mockMvc.perform(post(reserveUrl(event.getId(), seatId))
						.header("Authorization", "Bearer " + tokenFor(user)))
				.andExpect(status().isCreated());

		Seat seat = seatRepository.findById(seatId).orElseThrow();
		assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);

		var booking = bookingRepository.findBySeatId(seatId).stream()
				.filter(b -> b.getStatus() == BookingStatus.PENDING)
				.findFirst().orElseThrow();
		assertThat(booking.getUser().getId()).isEqualTo(user.getId());
		assertThat(booking.getExpiresAt())
				.isAfter(TEST_START.plusSeconds(14 * 60))
				.isBefore(TEST_START.plusSeconds(16 * 60));
	}

	@Test
	void duplicateActiveReservationBySameUserIsRejected() throws Exception {
		User user = newUser("duplicate-user@example.test");
		Event event = publishedEvent();
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001").orElseThrow().getId();
		String token = tokenFor(user);

		mockMvc.perform(post(reserveUrl(event.getId(), seatId))
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isCreated());

		mockMvc.perform(post(reserveUrl(event.getId(), seatId))
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Seat is no longer available."));

		long pendingForSeat = bookingRepository.findBySeatId(seatId).stream()
				.filter(b -> b.getStatus() == BookingStatus.PENDING)
				.count();
		assertThat(pendingForSeat).isEqualTo(1);
	}

	@Test
	void seatHeldByAnotherUserYieldsConflict() throws Exception {
		User first = newUser("holder@example.test");
		User second = newUser("latecomer@example.test");
		Event event = publishedEvent();
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001").orElseThrow().getId();

		mockMvc.perform(post(reserveUrl(event.getId(), seatId))
						.header("Authorization", "Bearer " + tokenFor(first)))
				.andExpect(status().isCreated());

		mockMvc.perform(post(reserveUrl(event.getId(), seatId))
						.header("Authorization", "Bearer " + tokenFor(second)))
				.andExpect(status().isConflict());
	}

	@Test
	void bookedSeatYieldsConflict() throws Exception {
		User user = newUser("booked-seat-user@example.test");
		Event event = publishedEvent();
		Seat seat = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S002").orElseThrow();
		seat.setStatus(SeatStatus.BOOKED);

		mockMvc.perform(post(reserveUrl(event.getId(), seat.getId()))
						.header("Authorization", "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict());
	}

	@Test
	void dueHoldExpiresAtomicallyToAvailableSeat() {
		User user = newUser("expiring-user@example.test");
		Event event = publishedEvent();
		Seat seat = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S003").orElseThrow();

		bookingService.reserve(user.getId(), event.getId(), seat.getId());
		assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);

		var booking = bookingRepository.findBySeatId(seat.getId()).stream().findFirst().orElseThrow();
		booking.setExpiresAt(Instant.now().minusSeconds(1));
		bookingRepository.saveAndFlush(booking);

		boolean expired = bookingService.expireIfDue(booking.getId());
		assertThat(expired).isTrue();

		assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
		assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.EXPIRED);
	}

	@Test
	void notYetDueHoldIsNotExpired() {
		User user = newUser("active-hold-user@example.test");
		Event event = publishedEvent();
		Seat seat = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001").orElseThrow();

		bookingService.reserve(user.getId(), event.getId(), seat.getId());
		var booking = bookingRepository.findBySeatId(seat.getId()).stream().findFirst().orElseThrow();

		assertThat(bookingService.expireIfDue(booking.getId())).isFalse();
		assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
		assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.PENDING);
	}

	@Test
	void releasedSeatCanBeReservedAgainAfterExpiration() {
		User first = newUser("re-reserve-a@example.test");
		User second = newUser("re-reserve-b@example.test");
		Event event = publishedEvent();
		Seat seat = seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001").orElseThrow();

		bookingService.reserve(first.getId(), event.getId(), seat.getId());
		var booking = bookingRepository.findBySeatId(seat.getId()).stream().findFirst().orElseThrow();
		booking.setExpiresAt(Instant.now().minusSeconds(1));
		bookingRepository.saveAndFlush(booking);
		bookingService.expireIfDue(booking.getId());

		var secondReservation = bookingService.reserve(second.getId(), event.getId(), seat.getId());
		assertThat(secondReservation.status()).isEqualTo(BookingStatus.PENDING);
		assertThat(seatRepository.findById(seat.getId()).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
	}

}
