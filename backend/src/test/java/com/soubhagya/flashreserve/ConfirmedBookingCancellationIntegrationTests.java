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
import com.soubhagya.flashreserve.exception.ServiceUnavailableException;
import com.soubhagya.flashreserve.payment.PaymentProvider;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.PaymentRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.security.JwtService;
import com.soubhagya.flashreserve.service.BookingService;

import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.HttpHeaders;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-level paid-cancellation flow tests for CONFIRMED bookings. The
 * {@link PaymentProvider} is mocked so no real Razorpay credentials or
 * network are used; booking/seat/payment state transitions run against the
 * real PostgreSQL database and the per-seat cancellation lock against the
 * real Redis instance, exactly like the existing payment integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m"
})
class ConfirmedBookingCancellationIntegrationTests {

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

	private User newUser(String email) {
		return userRepository.save(new User("User", email,
				passwordEncoder.encode("password-123"), UserRole.USER));
	}

	private String tokenFor(User user) {
		return jwtService.generateToken(user);
	}

	private Event newPublishedEvent() {
		Event event = new Event("Paid Event", "desc", "Venue", Instant.now().plusSeconds(86_400), 3);
		event.setStatus(EventStatus.PUBLISHED);
		event.setTicketPrice(new BigDecimal("499.00"));
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

	private UUID reserveOwn(User user, Event event, String seatNumber) {
		return bookingService.reserve(user.getId(), event.getId(), seatId(event, seatNumber)).bookingId();
	}

	/** Promotes a PENDING hold to the exact post-payment state triple. */
	private UUID confirmWithSuccessfulPayment(User user, Event event, String seatNumber) {
		UUID bookingId = reserveOwn(user, event, seatNumber);
		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		Seat seat = booking.getSeat();
		seat.setStatus(SeatStatus.BOOKED);
		seatRepository.saveAndFlush(seat);
		booking.setStatus(BookingStatus.CONFIRMED);
		bookingRepository.saveAndFlush(booking);

		Payment payment = new Payment(booking, event.getTicketPrice());
		payment.setPaymentReference("PAY-" + bookingId);
		payment.setRazorpayOrderId("order_" + bookingId);
		payment.setRazorpayPaymentId("pay_" + bookingId);
		payment.setStatus(PaymentStatus.SUCCESS);
		paymentRepository.saveAndFlush(payment);
		return bookingId;
	}

	private String cancelUrl(UUID bookingId) {
		return "/api/bookings/" + bookingId + "/cancel";
	}

	@Test
	void pendingBookingCancellationStillWorks() throws Exception {
		User user = newUser("reg-pending@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");

		mockMvc.perform(post(cancelUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CANCELLED);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.AVAILABLE);
		Mockito.verifyNoInteractions(paymentProvider);
	}

	@Test
	void confirmedBookingWithSuccessfulRefundIsCancelledAndSeatReleased() throws Exception {
		User user = newUser("refund-ok@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = confirmWithSuccessfulPayment(user, event, "S001");
		given(paymentProvider.refundPayment(anyString(), any(BigDecimal.class)))
				.willReturn("rfnd_test_1");

		mockMvc.perform(post(cancelUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CANCELLED);
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(payment.getRazorpayRefundId()).isEqualTo("rfnd_test_1");
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.AVAILABLE);
		verify(paymentProvider).refundPayment("pay_" + bookingId, new BigDecimal("499.00"));
	}

	@Test
	void refundFailureLeavesBookingConfirmedPaymentSuccessAndSeatBooked() throws Exception {
		User user = newUser("refund-fail@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = confirmWithSuccessfulPayment(user, event, "S001");
		given(paymentProvider.refundPayment(anyString(), any(BigDecimal.class)))
				.willThrow(new ServiceUnavailableException("Refund could not be processed. Please retry."));

		mockMvc.perform(post(cancelUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isServiceUnavailable());

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.SUCCESS);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.BOOKED);
		verify(paymentProvider, times(1)).refundPayment(anyString(), any(BigDecimal.class));
	}

	@Test
	void userCannotCancelAnotherUsersConfirmedBooking() throws Exception {
		User owner = newUser("refund-owner@example.test");
		User stranger = newUser("refund-stranger@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = confirmWithSuccessfulPayment(owner, event, "S001");

		mockMvc.perform(post(cancelUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(stranger)))
				.andExpect(status().isNotFound());

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.SUCCESS);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.BOOKED);
		verify(paymentProvider, never()).refundPayment(anyString(), any(BigDecimal.class));
	}

	@Test
	void confirmedBookingCannotBeCancelledAfterEventStart() throws Exception {
		User user = newUser("refund-started@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = confirmWithSuccessfulPayment(user, event, "S001");

		Event started = eventRepository.findById(event.getId()).orElseThrow();
		started.setEventDate(Instant.now().minusSeconds(60));
		eventRepository.saveAndFlush(started);

		mockMvc.perform(post(cancelUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message")
						.value("Cannot cancel a booking after the event has started."));

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.SUCCESS);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.BOOKED);
		verify(paymentProvider, never()).refundPayment(anyString(), any(BigDecimal.class));
	}

	@Test
	void expiredBookingCannotBeCancelled() throws Exception {
		User user = newUser("refund-expired@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");
		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		booking.setExpiresAt(Instant.now().minusSeconds(1));
		bookingRepository.saveAndFlush(booking);
		assertThat(bookingService.expireIfDue(bookingId)).isTrue();

		mockMvc.perform(post(cancelUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Cannot cancel booking in status EXPIRED"));

		verify(paymentProvider, never()).refundPayment(anyString(), any(BigDecimal.class));
	}

	@Test
	void repeatedCancellationAfterSuccessfulRefundIssuesNoSecondRefund() throws Exception {
		User user = newUser("refund-replay@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = confirmWithSuccessfulPayment(user, event, "S001");
		given(paymentProvider.refundPayment(anyString(), any(BigDecimal.class)))
				.willReturn("rfnd_test_1");

		mockMvc.perform(post(cancelUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());

		// A repeated cancellation must not re-refund, re-release the seat or
		// re-transition anything, and must not fail with a 500.
		mockMvc.perform(post(cancelUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Cannot cancel booking in status CANCELLED"));

		verify(paymentProvider, times(1)).refundPayment(anyString(), any(BigDecimal.class));
		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CANCELLED);
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		assertThat(payment.getRazorpayRefundId()).isEqualTo("rfnd_test_1");
		Seat seat = seatRepository.findById(seatId(event, "S001")).orElseThrow();
		assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
	}

}
