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
import java.util.concurrent.atomic.AtomicInteger;

import com.soubhagya.flashreserve.dto.payment.PaymentInitiationResponse;
import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.Payment;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Proves that concurrent payment initiations for the same booking serialize on
 * the per-seat distributed lock: exactly one Razorpay order is created, exactly
 * one Payment row exists, and every caller receives the persisted order id.
 * Runs without a test transaction so each operation commits on its own.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"reservation.hold-duration=15m"
})
class PaymentInitiationConcurrencyTests {

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
		Event event = new Event("Initiation Race", "d", "Hall", Instant.now().plusSeconds(86_400), 3);
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

	private UUID reserve(User user, Event event, String seatNumber) {
		return bookingService.reserve(user.getId(), event.getId(), seatId(event, seatNumber)).bookingId();
	}

	/**
	 * The provider returns a fresh order id on every call, so duplicate
	 * creations would be observable both as a call count above one and as
	 * diverging order ids in the responses.
	 */
	private AtomicInteger stubDistinctOrders() {
		AtomicInteger orderCounter = new AtomicInteger();
		given(paymentProvider.createOrder(anyString(), any(BigDecimal.class)))
				.willAnswer(invocation -> "order_race_" + orderCounter.incrementAndGet());
		given(paymentProvider.getClientKeyId()).willReturn("rzp_test_key_public");
		given(paymentProvider.getCurrency()).willReturn("INR");
		return orderCounter;
	}

	@Test
	void concurrentInitiationReusesOneOrderAndProducesOnePayment() throws Exception {
		stubDistinctOrders();
		User user = newUser("pay-init-race@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserve(user, event, "S001");

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<Object>> outcomes = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			outcomes.add(pool.submit((Callable<Object>) () -> {
				startGate.await();
				try {
					return paymentService.initiate(bookingId, user.getId());
				}
				catch (Throwable failure) {
					return failure;
				}
			}));
		}
		startGate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

		List<PaymentInitiationResponse> responses = new ArrayList<>();
		for (Future<Object> future : outcomes) {
			Object value = future.get();
			assertThat(value)
					.as("concurrent initiation must not fail unexpectedly")
					.isInstanceOf(PaymentInitiationResponse.class);
			responses.add((PaymentInitiationResponse) value);
		}

		Payment persisted = paymentRepository.findByBookingId(bookingId).orElseThrow();
		assertThat(paymentRepository.countByBookingId(bookingId)).isEqualTo(1);
		assertThat(responses.get(0).razorpayOrderId()).isEqualTo(persisted.getRazorpayOrderId());
		assertThat(responses.get(1).razorpayOrderId()).isEqualTo(persisted.getRazorpayOrderId());
		assertThat(responses.get(0).razorpayOrderId()).isEqualTo(responses.get(1).razorpayOrderId());
		verify(paymentProvider, times(1)).createOrder(anyString(), any(BigDecimal.class));
	}

	@Test
	void uniqueConstraintStillRejectsASecondPaymentForTheSameBooking() {
		User user = newUser("pay-init-unique@example.test");
		Event event = newPublishedEvent();
		UUID bookingId = reserve(user, event, "S001");
		Booking booking = bookingRepository.findById(bookingId).orElseThrow();

		Payment first = new Payment(booking, new BigDecimal("499.00"));
		first.setPaymentReference("PAY-" + UUID.randomUUID());
		paymentRepository.saveAndFlush(first);

		Payment second = new Payment(booking, new BigDecimal("499.00"));
		second.setPaymentReference("PAY-" + UUID.randomUUID());

		assertThatThrownBy(() -> paymentRepository.saveAndFlush(second))
				.as("uk_payments_booking_id must remain the final backstop")
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(paymentRepository.countByBookingId(bookingId)).isEqualTo(1);
	}

}
