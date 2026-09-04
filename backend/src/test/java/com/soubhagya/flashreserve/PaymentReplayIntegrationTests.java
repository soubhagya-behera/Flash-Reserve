package com.soubhagya.flashreserve;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.Payment;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.PaymentStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.payment.PaymentProvider;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.PaymentRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.security.JwtService;
import com.soubhagya.flashreserve.service.BookingService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Replay/idempotency regression tests for POST
 * /api/bookings/{bookingId}/payment/verify.
 *
 * <p>Deliberately runs WITHOUT a test-managed transaction: with
 * {@code spring.jpa.open-in-view=false} every repository call in production
 * commits in its own short transaction and returns detached entities, which
 * is exactly the boundary that produced the LazyInitializationException 500
 * when an already-SUCCESS payment was verified again. A @Transactional test
 * would keep one persistence context open for the whole request and mask the
 * bug. The {@link PaymentProvider} is mocked so no real Razorpay credentials
 * or network are involved.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m"
})
class PaymentReplayIntegrationTests {

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
	private PaymentRepository paymentRepository;

	@Autowired
	private BookingService bookingService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private PaymentProvider paymentProvider;

	private final List<UUID> createdUserIds = new ArrayList<>();

	private final List<UUID> createdEventIds = new ArrayList<>();

	@AfterEach
	void cleanDatabase() {
		for (UUID eventId : createdEventIds) {
			List<Booking> bookings = bookingRepository.findByEventId(eventId);
			for (Booking booking : bookings) {
				paymentRepository.findByBookingId(booking.getId()).ifPresent(paymentRepository::delete);
			}
			bookingRepository.deleteAll(bookings);
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
		Event event = new Event("Replay Event", "d", "Hall", Instant.now().plusSeconds(86_400), 3);
		event.setStatus(EventStatus.PUBLISHED);
		event.setTicketPrice(new BigDecimal("499.00"));
		Event saved = eventRepository.save(event);
		createdEventIds.add(saved.getId());
		List<Seat> seats = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			seats.add(new Seat(saved, String.format("S%03d", i)));
		}
		seatRepository.saveAll(seats);
		return saved;
	}

	private void stubOrderCreation() {
		given(paymentProvider.createOrder(anyString(), any(BigDecimal.class))).willReturn("order_replay_1");
		given(paymentProvider.getClientKeyId()).willReturn("rzp_test_key_public");
		given(paymentProvider.getCurrency()).willReturn("INR");
	}

	private UUID seatId(Event event, String seatNumber) {
		return seatRepository.findByEventIdAndSeatNumber(event.getId(), seatNumber).orElseThrow().getId();
	}

	private UUID reserveAndInitiate(User user, Event event) throws Exception {
		UUID bookingId = bookingService.reserve(user.getId(), event.getId(), seatId(event, "S001")).bookingId();
		mockMvc.perform(post("/api/bookings/" + bookingId + "/payment")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());
		return bookingId;
	}

	private String tokenFor(User user) {
		return jwtService.generateToken(user);
	}

	private String verifyUrl(UUID bookingId) {
		return "/api/bookings/" + bookingId + "/payment/verify";
	}

	private String verifyBody(String orderId, String paymentId, String signature) {
		return "{\"razorpayOrderId\":\"" + orderId + "\",\"razorpayPaymentId\":\"" + paymentId
				+ "\",\"razorpaySignature\":\"" + signature + "\"}";
	}
	/** A: the first valid verification confirms booking, books seat, succeeds payment. */
	@Test
	void firstValidVerificationConfirmsBookingAndBooksSeat() throws Exception {
		stubOrderCreation();
		User user = newUser("replay-first@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveAndInitiate(user, event);

		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);

		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(verifyBody("order_replay_1", "pay_replay_1", "sig")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
				.andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
				.andExpect(jsonPath("$.seatStatus").value("BOOKED"));

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
	}

	/**
	 * B + C: replaying verification after SUCCESS must return the recorded
	 * success state (200, never a 500), must NOT re-run HMAC verification,
	 * and must not create any duplicate payment/booking/seat state.
	 */
	@Test
	void replayAfterSuccessReturnsExistingSuccessStateWithoutDuplicates() throws Exception {
		stubOrderCreation();
		User user = newUser("replay-again@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);

		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);

		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(verifyBody("order_replay_1", "pay_replay_1", "sig")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentStatus").value("SUCCESS"));

		// Replay: a lost first response re-submitted with a garbage signature.
		// HMAC must not be evaluated again - the stored outcome is authoritative.
		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(verifyBody("order_replay_1", "pay_replay_1", "garbage-signature")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.razorpayPaymentId").value("pay_replay_1"))
				.andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
				.andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
				.andExpect(jsonPath("$.seatStatus").value("BOOKED"));

		// Exactly one payment for the booking, still SUCCESS with the same ids.
		assertThat(paymentRepository.findByRazorpayOrderId("order_replay_1")).isPresent();
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(payment.getRazorpayPaymentId()).isEqualTo("pay_replay_1");
		assertThat(payment.getRazorpayOrderId()).isEqualTo("order_replay_1");

		// Still exactly one booking for the seat, still CONFIRMED; seat BOOKED, once.
		assertThat(bookingRepository.findBySeatId(seat)).hasSize(1);
		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.BOOKED);

		// HMAC verification ran exactly once: for the original request only.
		verify(paymentProvider, times(1)).verifySignature(anyString(), anyString(), anyString());
	}
	/** D: an invalid signature on a first (non-replay) verification still fails. */
	@Test
	void invalidSignatureOnFirstVerificationIsStillRejected() throws Exception {
		stubOrderCreation();
		User user = newUser("replay-badsig@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);

		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(false);

		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(verifyBody("order_replay_1", "pay_replay_1", "bad-signature")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Invalid payment signature."));

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.PENDING);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.HELD);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.PENDING);
	}

	/** E: a USER can never verify (or replay) another user's booking. */
	@Test
	void userCannotVerifyAnotherUsersBooking() throws Exception {
		stubOrderCreation();
		User owner = newUser("replay-owner@example.test");
		User stranger = newUser("replay-stranger@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveAndInitiate(owner, event);

		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);

		// First attempt by a stranger: rejected before any signature work.
		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(stranger))
						.contentType(MediaType.APPLICATION_JSON)
						.content(verifyBody("order_replay_1", "pay_replay_1", "sig")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Booking not found: " + bookingId));

		// Owner confirms...
		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(owner))
						.contentType(MediaType.APPLICATION_JSON)
						.content(verifyBody("order_replay_1", "pay_replay_1", "sig")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentStatus").value("SUCCESS"));

		// ...and the stranger's replay is still a safe 404, not a 500.
		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(stranger))
						.contentType(MediaType.APPLICATION_JSON)
						.content(verifyBody("order_replay_1", "pay_replay_1", "sig")))
				.andExpect(status().isNotFound());

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
	}

}