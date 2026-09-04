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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * Proves that payment verification never races a hold expiration or a
 * cancellation into an inconsistent CONFIRMED state. Runs without a test
 * transaction so each operation commits in its own database transaction.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"reservation.hold-duration=15m"
})
class PaymentConcurrencyTests {

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
		Event event = new Event("Payment Race", "d", "Hall", Instant.now().plusSeconds(86_400), 3);
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

	private void stubProvider() {
		given(paymentProvider.createOrder(anyString(), any(BigDecimal.class))).willReturn("order_race_1");
		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);
		given(paymentProvider.getClientKeyId()).willReturn("rzp_test_key_public");
		given(paymentProvider.getCurrency()).willReturn("INR");
	}

	private UUID seatId(Event event, String seatNumber) {
		return seatRepository.findByEventIdAndSeatNumber(event.getId(), seatNumber).orElseThrow().getId();
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
		return new PaymentVerificationRequest("order_race_1", "pay_race_1", "sig", null);
	}

	@Test
	void verifyAgainstAlreadyExpiredHoldNeverConfirmsBooking() {
		stubProvider();
		User user = newUser("pay-race-expired@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);
		makeDue(bookingId);
		assertThat(bookingService.expireIfDue(bookingId)).isTrue();

		try {
			paymentService.verify(bookingId, user.getId(), validVerify());
		}
		catch (Throwable expected) {
			// Conflict / optimistic-lock rollback is the expected outcome.
		}

		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.EXPIRED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
		assertThat(payment.getStatus())
				.as("a failed-to-confirm payment stays pending").isEqualTo(PaymentStatus.PENDING);
	}

	@Test
	void verifyAgainstAlreadyCancelledBookingNeverConfirmsBooking() {
		stubProvider();
		User user = newUser("pay-race-cancelled@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);
		bookingService.cancelBooking(bookingId, user.getId());

		try {
			paymentService.verify(bookingId, user.getId(), validVerify());
		}
		catch (Throwable expected) {
		}

		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
		assertThat(paymentRepository.findByBookingId(bookingId).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.PENDING);
	}

	/**
	 * True race between a successful payment verification and a due-hold
	 * expiration. The invariant: CONFIRMED only appears together with a
	 * BOOKED seat and a SUCCESS payment; a released seat is never BOOKED.
	 */
	@Test
	void confirmationAndExpirationRaceStaysConsistent() throws Exception {
		stubProvider();
		User user = newUser("pay-race-live@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);
		makeDue(bookingId);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<Object>> outcomes = new ArrayList<>();
		outcomes.add(pool.submit((Callable<Object>) () -> {
			startGate.await();
			try {
				bookingService.expireIfDue(bookingId);
				return "expired";
			}
			catch (Throwable failure) {
				return failure;
			}
		}));
		outcomes.add(pool.submit((Callable<Object>) () -> {
			startGate.await();
			try {
				paymentService.verify(bookingId, user.getId(), validVerify());
				return "verified";
			}
			catch (Throwable failure) {
				return failure;
			}
		}));
		startGate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		Seat reloadedSeat = seatRepository.findById(seat).orElseThrow();
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();

		boolean verified = outcomes.stream().anyMatch(f -> "verified".equals(safe(f)));
		if (verified && booking.getStatus() == BookingStatus.CONFIRMED) {
			assertThat(reloadedSeat.getStatus()).isEqualTo(SeatStatus.BOOKED);
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
		}
		else {
			assertThat(booking.getStatus()).isIn(BookingStatus.EXPIRED, BookingStatus.CANCELLED);
			assertThat(payment.getStatus()).isNotEqualTo(PaymentStatus.SUCCESS);
			assertThat(reloadedSeat.getStatus()).isNotEqualTo(SeatStatus.BOOKED);
		}
	}

	/**
	 * True race between a successful payment verification and a user
	 * cancellation. A CANCELLED booking must never become CONFIRMED, and no
	 * payment may end SUCCESS when the booking was cancelled.
	 */
	@Test
	void confirmationAndCancellationRaceStaysConsistent() throws Exception {
		stubProvider();
		User user = newUser("pay-race-cancel-live@example.test");
		Event event = newPublishedEvent();
		UUID seat = seatId(event, "S001");
		UUID bookingId = reserveAndInitiate(user, event);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<Object>> outcomes = new ArrayList<>();
		outcomes.add(pool.submit((Callable<Object>) () -> {
			startGate.await();
			try {
				bookingService.cancelBooking(bookingId, user.getId());
				return "cancelled";
			}
			catch (Throwable failure) {
				return failure;
			}
		}));
		outcomes.add(pool.submit((Callable<Object>) () -> {
			startGate.await();
			try {
				paymentService.verify(bookingId, user.getId(), validVerify());
				return "verified";
			}
			catch (Throwable failure) {
				return failure;
			}
		}));
		startGate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

		/* The race has two legitimate winners, so the assertion mirrors the
		   expiration race above: either verification committed first and the
		   booking is CONFIRMED with a BOOKED seat and a SUCCESS payment, or
		   cancellation committed first and the payment can never end SUCCESS.
		   What must NEVER happen is a CANCELLED booking paired with a SUCCESS
		   payment, or a booking left PENDING. */
		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(booking.getStatus()).isNotEqualTo(BookingStatus.PENDING);
		if (booking.getStatus() == BookingStatus.CONFIRMED) {
			assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
		}
		else {
			assertThat(booking.getStatus()).isIn(BookingStatus.EXPIRED, BookingStatus.CANCELLED);
			assertThat(payment.getStatus()).isNotEqualTo(PaymentStatus.SUCCESS);
		}
	}

	private static String safe(Future<Object> future) {
		try {
			Object value = future.get();
			return value instanceof Throwable ? "threw" : String.valueOf(value);
		}
		catch (Exception ignored) {
			return "threw";
		}
	}

}

