package com.soubhagya.flashreserve;

import java.math.BigDecimal;
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

import com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest;
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
import com.soubhagya.flashreserve.service.BookingService;
import com.soubhagya.flashreserve.service.PaymentService;
import com.soubhagya.flashreserve.service.SeatStatusPublisher;
import com.soubhagya.flashreserve.service.SeatUpdateHub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Commit-2 focused tests for payment state transitions + concurrency hardening. Covers A-H. */
@SpringBootTest
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m"
})
class PaymentTransitionsCommit2Tests {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private PaymentService paymentService;

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
	private PasswordEncoder passwordEncoder;

	@MockitoBean
	private PaymentProvider paymentProvider;

	@MockitoSpyBean
	private SeatStatusPublisher seatStatusPublisher;

	@MockitoSpyBean
	private SeatUpdateHub seatUpdateHub;

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
		Event event = new Event("Commit2 Event", "d", "Hall", Instant.now().plusSeconds(86_400), 3);
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

	private UUID seatId(Event event, String seatNumber) {
		return seatRepository.findByEventIdAndSeatNumber(event.getId(), seatNumber).orElseThrow().getId();
	}

	private void stubProvider() {
		given(paymentProvider.createOrder(anyString(), any(BigDecimal.class))).willReturn("order_commit2_1");
		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);
		given(paymentProvider.getClientKeyId()).willReturn("rzp_test_key_public");
		given(paymentProvider.getCurrency()).willReturn("INR");
	}

	private UUID reserveAndInitiate(User user, Event event) {
		UUID bookingId = bookingService.reserve(user.getId(), event.getId(), seatId(event, "S001")).bookingId();
		paymentService.initiate(bookingId, user.getId());
		return bookingId;
	}

	private void makeDue(UUID bookingId) {
		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		booking.setExpiresAt(Instant.now().minusSeconds(1));
		bookingRepository.saveAndFlush(booking);
	}

	private static PaymentVerificationRequest validVerify() {
		return new PaymentVerificationRequest("order_commit2_1", "pay_commit2_1", "sig", null);
	}

	private static PaymentVerificationRequest failedVerify() {
		return new PaymentVerificationRequest("order_commit2_1", "pay_commit2_1", "sig", "FAILED");
	}

	// A. Existing /verify success still works.
	@Test
	void verifySuccessStillWorks() {
		stubProvider();
		User user = newUser("commit2-a-success@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveAndInitiate(user, event);
		UUID seat = seatId(event, "S001");

		var response = paymentService.verify(bookingId, user.getId(), validVerify());

		assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(response.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(response.seatStatus()).isEqualTo(SeatStatus.BOOKED);
		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.BOOKED);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
	}

	// B. /verify replay after SUCCESS is idempotent and no second SSE.
	@Test
	void verifyReplayAfterSuccessIsIdempotentNoSecondSse() {
		stubProvider();
		User user = newUser("commit2-b-replay@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveAndInitiate(user, event);

		paymentService.verify(bookingId, user.getId(), validVerify());
		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);

		var replay = paymentService.verify(bookingId, user.getId(), validVerify());

		assertThat(replay.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(replay.bookingStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getRazorpayPaymentId()).isEqualTo("pay_commit2_1");
		verify(seatStatusPublisher, never()).publishAfterCommit(any());
		verify(seatUpdateHub, never()).broadcast(any());
		verify(paymentProvider, times(1)).verifySignature(anyString(), anyString(), anyString());
	}

	// C. Concurrent confirmation attempts serialize correctly.
	@Test
	void concurrentConfirmationSerializes() throws Exception {
		stubProvider();
		User user = newUser("commit2-c-concurrent@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch gate = new CountDownLatch(1);
		List<Future<Object>> futures = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			futures.add(pool.submit((Callable<Object>) () -> {
				gate.await();
				try {
					return paymentService.verify(bookingId, user.getId(), validVerify());
				}
				catch (Throwable t) {
					return t;
				}
			}));
		}
		gate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		Seat s = seatRepository.findById(seat).orElseThrow();
		Payment p = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(s.getStatus()).isEqualTo(SeatStatus.BOOKED);
		assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
	}

	// D. Confirmation after expiration cannot resurrect.
	@Test
	void confirmationAfterExpirationCannotResurrect() {
		stubProvider();
		User user = newUser("commit2-d-expired@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);
		makeDue(bookingId);
		assertThat(bookingService.expireIfDue(bookingId)).isTrue();

		try {
			paymentService.verify(bookingId, user.getId(), validVerify());
		}
		catch (Throwable ignored) {
		}

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
	}

	// E. Expiration marks PENDING Payment as FAILED.
	@Test
	void expirationMarksPendingPaymentAsFailed() {
		stubProvider();
		User user = newUser("commit2-e-expire-pay@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);
		makeDue(bookingId);

		assertThat(bookingService.expireIfDue(bookingId)).isTrue();

		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
	}

	// F. Expiration and confirmation race: deterministic invariant.
	@Test
	void expirationAndConfirmationRaceInvariant() throws Exception {
		stubProvider();
		User user = newUser("commit2-f-race@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);
		makeDue(bookingId);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch gate = new CountDownLatch(1);
		Future<Object> expireF = pool.submit((Callable<Object>) () -> {
			gate.await();
			try {
				return bookingService.expireIfDue(bookingId);
			}
			catch (Throwable t) {
				return t;
			}
		});
		Future<Object> verifyF = pool.submit((Callable<Object>) () -> {
			gate.await();
			try {
				return paymentService.verify(bookingId, user.getId(), validVerify());
			}
			catch (Throwable t) {
				return t;
			}
		});
		gate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		assertThat(expireF.get()).isNotNull();
		assertThat(verifyF.get()).isNotNull();

		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		Seat s = seatRepository.findById(seat).orElseThrow();
		Payment p = paymentRepository.findByBookingId(bookingId).orElseThrow();
		boolean successPath = booking.getStatus() == BookingStatus.CONFIRMED
				&& s.getStatus() == SeatStatus.BOOKED
				&& p.getStatus() == PaymentStatus.SUCCESS;
		boolean expiredPath = booking.getStatus() == BookingStatus.EXPIRED
				&& s.getStatus() == SeatStatus.AVAILABLE
				&& p.getStatus() == PaymentStatus.FAILED;
		assertThat(successPath || expiredPath).isTrue();
		assertThat(successPath && expiredPath).isFalse();
		// Never allow mixed states
		assertThat(p.getStatus() == PaymentStatus.SUCCESS && booking.getStatus() == BookingStatus.EXPIRED).isFalse();
		assertThat(p.getStatus() == PaymentStatus.FAILED && booking.getStatus() == BookingStatus.CONFIRMED).isFalse();
	}

	// G. Payment failure cannot overwrite SUCCESS.
	@Test
	void paymentFailureCannotOverwriteSuccess() {
		stubProvider();
		User user = newUser("commit2-g-fail-success@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveAndInitiate(user, event);
		UUID seat = seatId(event, "S001");

		paymentService.verify(bookingId, user.getId(), validVerify());
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);

		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);
		var result = paymentService.verify(bookingId, user.getId(), failedVerify());

		assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.BOOKED);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
		verify(seatStatusPublisher, never()).publishAfterCommit(any());
	}

	// H. SSE emitted exactly once for real and zero for no-op.
	@Test
	void sseEmittedExactlyOnceForRealZeroForNoOp() {
		stubProvider();
		User user = newUser("commit2-h-sse@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveAndInitiate(user, event);
		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);
		paymentService.verify(bookingId, user.getId(), validVerify());
		verify(seatStatusPublisher, times(1)).publishAfterCommit(any());
		verify(seatUpdateHub, times(1)).broadcast(any());
		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);
		paymentService.verify(bookingId, user.getId(), validVerify());
		verify(seatStatusPublisher, never()).publishAfterCommit(any());
		verify(seatUpdateHub, never()).broadcast(any());
		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		booking.setExpiresAt(Instant.now().minusSeconds(60));
		bookingRepository.saveAndFlush(booking);
		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);
		assertThat(bookingService.expireIfDue(bookingId)).isFalse();
		verify(seatStatusPublisher, never()).publishAfterCommit(any());
	}

	@Test
	void sseAvailableEmittedOnceAndNotOnSecondExpire() {
		stubProvider();
		User user = newUser("commit2-h-sse-expire@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserveAndInitiate(user, event);
		makeDue(bookingId);

		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);
		assertThat(bookingService.expireIfDue(bookingId)).isTrue();
		verify(seatStatusPublisher, times(1)).publishAfterCommit(any());

		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);
		assertThat(bookingService.expireIfDue(bookingId)).isFalse();
		verify(seatStatusPublisher, never()).publishAfterCommit(any());
	}
}
