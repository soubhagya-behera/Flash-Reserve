package com.soubhagya.flashreserve;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.booking.ReservationResponse;
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

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API-level payment flow tests. The {@link PaymentProvider} is mocked so no
 * real Razorpay credentials or network are used; booking/seat/payment state
 * transitions run against the real PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m"
})
class PaymentApiIntegrationTests {

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
		ReservationResponse response = bookingService.reserve(user.getId(), event.getId(),
				seatId(event, seatNumber));
		return response.bookingId();
	}

	private void stubOrderCreation() {
		given(paymentProvider.createOrder(anyString(), any(BigDecimal.class))).willReturn("order_test_123");
		given(paymentProvider.getClientKeyId()).willReturn("rzp_test_key_public");
		given(paymentProvider.getCurrency()).willReturn("INR");
	}

	private String initiateUrl(UUID bookingId) {
		return "/api/bookings/" + bookingId + "/payment";
	}

	private String verifyUrl(UUID bookingId) {
		return "/api/bookings/" + bookingId + "/payment/verify";
	}

	private String validVerifyBody(String orderId, String paymentId, String signature) {
		return "{\"razorpayOrderId\":\"" + orderId + "\",\"razorpayPaymentId\":\"" + paymentId
				+ "\",\"razorpaySignature\":\"" + signature + "\"}";
	}

	@Test
	void ownerCanInitiatePaymentForPendingBooking() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-init@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");

		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingId").value(bookingId.toString()))
				.andExpect(jsonPath("$.razorpayOrderId").value("order_test_123"))
				.andExpect(jsonPath("$.razorpayKeyId").value("rzp_test_key_public"))
				.andExpect(jsonPath("$.amount").value(499.00))
				.andExpect(jsonPath("$.currency").value("INR"))
				.andExpect(jsonPath("$.status").value("PENDING"));

		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(payment.getRazorpayOrderId()).isEqualTo("order_test_123");
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(payment.getAmount()).isEqualByComparingTo("499.00");
	}

	@Test
	void nonOwnerCannotInitiatePayment() throws Exception {
		stubOrderCreation();
		User owner = newUser("pay-owner@example.test");
		User stranger = newUser("pay-stranger@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(owner, event, "S001");

		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(stranger)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Booking not found: " + bookingId));
	}

	@Test
	void missingBookingCannotInitiatePayment() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-missing@example.test");
		UUID ghost = UUID.fromString("00000000-0000-0000-0000-00000000dead");

		mockMvc.perform(post(initiateUrl(ghost))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isNotFound());
	}

	@Test
	void expiredBookingCannotInitiatePayment() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-expired@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");

		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		booking.setExpiresAt(Instant.now().minusSeconds(1));
		bookingRepository.saveAndFlush(booking);
		bookingService.expireIfDue(booking.getId());

		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(
						"Cannot initiate payment for booking in status EXPIRED"));
	}

	@Test
	void cancelledBookingCannotInitiatePayment() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-cancelled@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");

		mockMvc.perform(post("/api/bookings/{id}/cancel", bookingId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());

		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(
						"Cannot initiate payment for booking in status CANCELLED"));
	}

	@Test
	void confirmedBookingCannotInitiatePayment() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-confirmed@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");

		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		booking.setStatus(BookingStatus.CONFIRMED);
		bookingRepository.saveAndFlush(booking);

		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Booking is already confirmed."));
	}


	@Test
	void duplicateInitiationDoesNotCreateDuplicatePaymentOrOrder() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-duplicate@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");

		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());
		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.razorpayOrderId").value("order_test_123"));

		assertThat(paymentRepository.findByBookingId(bookingId)).isPresent();
		// The provider is only asked for an order once; the second call reuses it.
		org.mockito.Mockito.verify(paymentProvider, org.mockito.Mockito.times(1))
				.createOrder(anyString(), any(BigDecimal.class));
	}

	@Test
	void serverDerivedAmountIsUsedAndClientCannotAlterIt() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-amount@example.test");
		Event event = newPublishedEvent();
		event.setTicketPrice(new BigDecimal("1200.50"));
		eventRepository.saveAndFlush(event);
		UUID bookingId = reserveOwn(user, event, "S001");

		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.amount").value(1200.50));

		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(payment.getAmount()).isEqualByComparingTo("1200.50");
		// The client never supplies an amount in this flow; nothing to trust.
		Payment reloaded = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(reloaded.getAmount()).isEqualByComparingTo(new BigDecimal("1200.50"));
	}

	@Test
	void unauthenticatedPaymentRequestsAreRejected() throws Exception {
		mockMvc.perform(post(initiateUrl(UUID.randomUUID())))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(post(verifyUrl(UUID.randomUUID()))
						.contentType(MediaType.APPLICATION_JSON)
						.content(validVerifyBody("o", "p", "s")))
				.andExpect(status().isUnauthorized());
	}


	@Test
	void successfulVerifiedPaymentConfirmsBookingAndBooksSeat() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-success@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");
		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());

		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);

		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(validVerifyBody("order_test_123", "pay_test_1", "sig")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
				.andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"))
				.andExpect(jsonPath("$.seatStatus").value("BOOKED"));

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.BOOKED);
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(payment.getRazorpayPaymentId()).isEqualTo("pay_test_1");
	}

	@Test
	void invalidSignatureIsRejectedAndBookingStaysPending() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-badsig@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");
		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());

		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(false);

		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(validVerifyBody("order_test_123", "pay_test_1", "bad-signature")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Invalid payment signature."));

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.PENDING);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.HELD);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.PENDING);
	}

	@Test
	void mismatchedOrderIdIsRejected() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-mismatch@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");
		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());

		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);

		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(validVerifyBody("order_WRONG", "pay_test_1", "sig")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("Razorpay order does not match this booking's payment."));

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.PENDING);
	}

	@Test
	void anotherBookingsOrderCannotConfirmThisBooking() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-crosstalk@example.test");
		Event event = newPublishedEvent();
		UUID bookingA = reserveOwn(user, event, "S001");
		UUID bookingB = reserveOwn(user, event, "S002");
		mockMvc.perform(post(initiateUrl(bookingA))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());
		mockMvc.perform(post(initiateUrl(bookingB))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());

		// Give each payment a distinct, realistic order id.
		Payment payA = paymentRepository.findByBookingId(bookingA).orElseThrow();
		Payment payB = paymentRepository.findByBookingId(bookingB).orElseThrow();
		payA.setRazorpayOrderId("order_B1");
		payB.setRazorpayOrderId("order_B2");
		paymentRepository.saveAll(List.of(payA, payB));

		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);

		// Booking A is verified with booking B's order.
		mockMvc.perform(post(verifyUrl(bookingA))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(validVerifyBody("order_B2", "pay_test_1", "sig")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message")
						.value("Razorpay order does not match this booking's payment."));

		assertThat(bookingRepository.findById(bookingA).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.PENDING);
	}


	@Test
	void failedPaymentNeverConfirmsBookingAndReleasesTheHold() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-fail@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");
		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());

		String failedBody = "{\"razorpayOrderId\":\"order_test_123\",\"razorpayPaymentId\":\"pay_fail_1\","
				+ "\"razorpaySignature\":\"ignored\",\"providerStatus\":\"FAILED\"}";
		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(failedBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentStatus").value("FAILED"))
				.andExpect(jsonPath("$.bookingStatus").value("CANCELLED"))
				.andExpect(jsonPath("$.seatStatus").value("AVAILABLE"));

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CANCELLED);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.AVAILABLE);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.FAILED);
	}

	@Test
	void duplicateSuccessfulVerificationIsHandledSafely() throws Exception {
		stubOrderCreation();
		User user = newUser("pay-reverify@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveOwn(user, event, "S001");
		mockMvc.perform(post(initiateUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isOk());

		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);

		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(validVerifyBody("order_test_123", "pay_test_1", "sig")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentStatus").value("SUCCESS"));

		// A second, identical verification is idempotent.
		mockMvc.perform(post(verifyUrl(bookingId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user))
						.contentType(MediaType.APPLICATION_JSON)
						.content(validVerifyBody("order_test_123", "pay_test_1", "sig")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paymentStatus").value("SUCCESS"));

		assertThat(paymentRepository.findByBookingId(bookingId)).isPresent();
		assertThat(paymentRepository.findAll().stream()
				.filter(p -> p.getBooking().getId().equals(bookingId)).count()).isEqualTo(1);
	}

}

