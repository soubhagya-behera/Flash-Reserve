package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import com.soubhagya.flashreserve.security.JwtService;
import com.soubhagya.flashreserve.service.BookingService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.HttpHeaders;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class BookingApiIntegrationTests {

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

	private Event newPublishedEvent() {
		Event event = new Event("Test Event", "desc", "Venue", Instant.now().plusSeconds(86_400), 3);
		event.setStatus(EventStatus.PUBLISHED);
		event = eventRepository.save(event);
		List<Seat> seats = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			seats.add(new Seat(event, String.format("S%03d", i)));
		}
		seatRepository.saveAll(seats);
		return event;
	}

	private UUID seatId(Event event, String seatNumber) {
		return seatRepository.findByEventIdAndSeatNumber(event.getId(), seatNumber).orElseThrow().getId();
	}

	private String reserve(Event event, String seatNumber, String token) throws Exception {
		String url = "/api/events/" + event.getId() + "/seats/" + seatId(event, seatNumber) + "/reservations";
		MvcResult result = mockMvc.perform(post(url)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isCreated())
				.andReturn();
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.bookingId");
	}

	private Booking bookingOf(Event event, String seatNumber) {
		Seat seat = seatRepository.findByEventIdAndSeatNumber(event.getId(), seatNumber).orElseThrow();
		return bookingRepository.findBySeatId(seat.getId()).stream().findFirst().orElseThrow();
	}

	@Test
	void authenticatedUserCanListOwnBookings() throws Exception {
		User user = newUser("list-own@example.test");
		Event event = newPublishedEvent();
		String bookingId = reserve(event, "S001", tokenFor(user));

		mockMvc.perform(get("/api/bookings")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].bookingId").value(bookingId))
				.andExpect(jsonPath("$.content[0].status").value("PENDING"))
				.andExpect(jsonPath("$.content[0].seatNumber").value("S001"))
				.andExpect(jsonPath("$.content[0].eventName").value(event.getName()))
				.andExpect(jsonPath("$.page.totalElements").value(1));
	}

	@Test
	void bookingListNeverIncludesAnotherUsersBooking() throws Exception {
		User alice = newUser("alice-bookings@example.test");
		User bob = newUser("bob-bookings@example.test");
		Event event = newPublishedEvent();
		String aliceBooking = reserve(event, "S001", tokenFor(alice));
		reserve(event, "S002", tokenFor(bob));

		mockMvc.perform(get("/api/bookings")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(alice)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].bookingId").value(aliceBooking));
	}

	@Test
	void bookingListOrdersNewestFirstAndPaginates() throws Exception {
		User user = newUser("paging@example.test");
		String token = tokenFor(user);
		Event event = newPublishedEvent();
		String oldest = reserve(event, "S001", token);
		String middle = reserve(event, "S002", token);
		String newest = reserve(event, "S003", token);

		mockMvc.perform(get("/api/bookings")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(3))
				.andExpect(jsonPath("$.content[0].bookingId").value(newest))
				.andExpect(jsonPath("$.content[1].bookingId").value(middle))
				.andExpect(jsonPath("$.content[2].bookingId").value(oldest));

		mockMvc.perform(get("/api/bookings")
						.param("size", "2")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.page.totalElements").value(3));
	}

	@Test
	void ownerCanRetrieveOwnBookingDetail() throws Exception {
		User user = newUser("detail-owner@example.test");
		Event event = newPublishedEvent();
		String bookingId = reserve(event, "S001", tokenFor(user));

		mockMvc.perform(get("/api/bookings/{id}", bookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingId").value(bookingId))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.eventId").value(event.getId().toString()))
				.andExpect(jsonPath("$.seatNumber").value("S001"))
				.andExpect(jsonPath("$.expiresAt").isNotEmpty());
	}

	@Test
	void missingBookingReturns404() throws Exception {
		User user = newUser("detail-missing@example.test");

		mockMvc.perform(get("/api/bookings/{id}", UUID.randomUUID())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void anotherUsersBookingIsNotRevealed() throws Exception {
		User alice = newUser("mine@example.test");
		User bob = newUser("not-mine@example.test");
		Event event = newPublishedEvent();
		String aliceBooking = reserve(event, "S001", tokenFor(alice));

		mockMvc.perform(get("/api/bookings/{id}", aliceBooking)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(bob)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Booking not found: " + aliceBooking));
	}

	@Test
	void ownerCanCancelPendingBookingAndSeatIsReleased() throws Exception {
		User user = newUser("cancel-owner@example.test");
		Event event = newPublishedEvent();
		String bookingId = reserve(event, "S001", tokenFor(user));
		assertThat(bookingOf(event, "S001").getStatus()).isEqualTo(BookingStatus.PENDING);

		mockMvc.perform(post("/api/bookings/{id}/cancel", bookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingId").value(bookingId))
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		Booking booking = bookingRepository.findById(UUID.fromString(bookingId)).orElseThrow();
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(seatRepository.findById(booking.getSeat().getId()).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.AVAILABLE);
	}

	@Test
	void cancellationReleasesOnlyTheAssociatedSeat() throws Exception {
		User alice = newUser("cancel-a@example.test");
		User bob = newUser("cancel-b@example.test");
		Event event = newPublishedEvent();
		String aliceBooking = reserve(event, "S001", tokenFor(alice));
		reserve(event, "S002", tokenFor(bob));

		mockMvc.perform(post("/api/bookings/{id}/cancel", aliceBooking)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(alice)))
				.andExpect(status().isOk());

		assertThat(bookingOf(event, "S001").getStatus()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.AVAILABLE);
		assertThat(bookingOf(event, "S002").getStatus())
				.as("the other user's booking is untouched")
				.isEqualTo(BookingStatus.PENDING);
		assertThat(seatRepository.findById(seatId(event, "S002")).orElseThrow().getStatus())
				.as("the other user's held seat is untouched")
				.isEqualTo(SeatStatus.HELD);
	}

	@Test
	void releasedSeatCanBeReservedAgainAfterCancellation() throws Exception {
		User first = newUser("release-a@example.test");
		User second = newUser("release-b@example.test");
		Event event = newPublishedEvent();
		String bookingId = reserve(event, "S001", tokenFor(first));

		mockMvc.perform(post("/api/bookings/{id}/cancel", bookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(first)))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/events/" + event.getId() + "/seats/" + seatId(event, "S001") + "/reservations")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(second)))
				.andExpect(status().isCreated());
	}

	@Test
	void cancelledBookingCannotBeCancelledAgain() throws Exception {
		User user = newUser("cancel-twice@example.test");
		Event event = newPublishedEvent();
		String bookingId = reserve(event, "S001", tokenFor(user));

		mockMvc.perform(post("/api/bookings/{id}/cancel", bookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());

		mockMvc.perform(post("/api/bookings/{id}/cancel", bookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Cannot cancel booking in status CANCELLED"));
	}

	@Test
	void expiredBookingCannotBeCancelledAndSeatIsNotTouchedAgain() throws Exception {
		User user = newUser("cancel-expired@example.test");
		Event event = newPublishedEvent();
		String bookingId = reserve(event, "S001", tokenFor(user));

		Booking booking = bookingRepository.findById(UUID.fromString(bookingId)).orElseThrow();
		booking.setExpiresAt(Instant.now().minusSeconds(1));
		bookingRepository.saveAndFlush(booking);
		assertThat(bookingService.expireIfDue(booking.getId())).isTrue();

		mockMvc.perform(post("/api/bookings/{id}/cancel", bookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Cannot cancel booking in status EXPIRED"));

		assertThat(bookingRepository.findById(UUID.fromString(bookingId)).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.EXPIRED);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.AVAILABLE);
	}

	@Test
	void confirmedBookingCannotBeCancelled() throws Exception {
		User user = newUser("cancel-confirmed@example.test");
		Event event = newPublishedEvent();
		String bookingId = reserve(event, "S001", tokenFor(user));

		Booking booking = bookingRepository.findById(UUID.fromString(bookingId)).orElseThrow();
		booking.setStatus(BookingStatus.CONFIRMED);
		bookingRepository.saveAndFlush(booking);

		mockMvc.perform(post("/api/bookings/{id}/cancel", bookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Cannot cancel booking in status CONFIRMED"));
	}

	@Test
	void unauthenticatedBookingRequestsAreRejected() throws Exception {
		mockMvc.perform(get("/api/bookings"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(post("/api/bookings/{id}/cancel", UUID.randomUUID()))
				.andExpect(status().isUnauthorized());
	}

}