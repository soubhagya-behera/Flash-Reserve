package com.soubhagya.flashreserve;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import tools.jackson.databind.ObjectMapper;

import com.soubhagya.flashreserve.dto.booking.ReservationResponse;
import com.soubhagya.flashreserve.dto.event.SeatStatusEvent;
import com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest;
import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.Payment;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
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
import com.soubhagya.flashreserve.service.SeatStreamIdentity;
import com.soubhagya.flashreserve.service.SeatUpdateHub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

import org.redisson.client.RedisException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * Real-time seat-update publication tests. Every business state change must
 * publish a public seat event strictly AFTER its transaction commits, events
 * must be scoped to their own event id, own Redis echoes suppressed, and a
 * Redis Pub/Sub outage must never affect the transactional outcome.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"reservation.hold-duration=15m"
})
class SeatUpdatePublicationTests {

	@Autowired
	private BookingService bookingService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private SeatStatusPublisher seatStatusPublisher;

	@Autowired
	private SeatStreamIdentity identity;

	@Autowired
	private ObjectMapper objectMapper;

	@MockitoSpyBean
	private SeatUpdateHub seatUpdateHub;

	@MockitoSpyBean
	private org.redisson.api.RTopic seatUpdatesTopic;

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
			bookingRepository.findByEventId(eventId).forEach(booking -> {
				paymentRepository.findByBookingId(booking.getId()).ifPresent(paymentRepository::delete);
				bookingRepository.delete(booking);
			});
			seatRepository.findByEventId(eventId).forEach(seatRepository::delete);
			eventRepository.deleteById(eventId);
		}
		createdEventIds.clear();
		createdUserIds.forEach(userRepository::deleteById);
		createdUserIds.clear();
	}

	private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

	private User newUser(String email) {
		// Unique per JVM run: leftover rows from an aborted earlier run can
		// never violate the unique email constraint.
		User user = userRepository.save(new User("User", email.replace("@", "-" + RUN_ID + "@"),
				passwordEncoder.encode("password-123"), UserRole.USER));
		createdUserIds.add(user.getId());
		return user;
	}

	private Event newPublishedEvent(String name) {
		Event published = new Event(name, "d", "Hall", Instant.now().plusSeconds(86_400), 3);
		published.setStatus(EventStatus.PUBLISHED);
		published.setTicketPrice(new BigDecimal("499.00"));
		Event saved = eventRepository.save(published);
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

	/** Records every event fanned out to local SSE subscribers. */
	private List<SeatStatusEvent> captureLocalBroadcasts() {
		List<SeatStatusEvent> received = new CopyOnWriteArrayList<>();
		Mockito.doAnswer(invocation -> {
			received.add(invocation.getArgument(0));
			return null;
		}).when(seatUpdateHub).broadcast(any());
		return received;
	}

	private List<SeatStatusEvent> eventsFor(List<SeatStatusEvent> events, Event event) {
		return events.stream().filter(candidate -> candidate.eventId().equals(event.getId())).toList();
	}

	private void attachOrderPayment(UUID bookingId, Event event) {
		Payment payment = new Payment(bookingRepository.findById(bookingId).orElseThrow(),
				event.getTicketPrice());
		payment.setPaymentReference("PAY-" + bookingId);
		payment.setRazorpayOrderId("order_" + bookingId);
		paymentRepository.saveAndFlush(payment);
	}

	@Test
	void reservationPublishesHeldEventWithCommittedVersion() {
		User user = newUser("stream-reserve@example.test");
		Event event = newPublishedEvent("Stream Hold Event");
		UUID seat = seatId(event, "S001");
		List<SeatStatusEvent> events = captureLocalBroadcasts();

		bookingService.reserve(user.getId(), event.getId(), seat);

		assertThat(eventsFor(events, event)).hasSize(1);
		SeatStatusEvent held = eventsFor(events, event).get(0);
		assertThat(held.seatId()).isEqualTo(seat);
		assertThat(held.seatNumber()).isEqualTo("S001");
		assertThat(held.status()).isEqualTo(SeatStatus.HELD);
		assertThat(held.expiresAt()).isNotNull();
		// The version must equal the committed Seat @Version (1 after the
		// first write), so clients can order events against the snapshot.
		assertThat(held.seatVersion()).isEqualTo(seatRepository.findById(seat).orElseThrow().getVersion());
	}

	@Test
	void paymentConfirmationPublishesBookedEvent() {
		User user = newUser("stream-pay@example.test");
		Event event = newPublishedEvent("Stream Pay Event");
		UUID bookingId = reserve(user, event, "S001");
		attachOrderPayment(bookingId, event);
		List<SeatStatusEvent> events = captureLocalBroadcasts();
		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);

		paymentService.verify(bookingId, user.getId(), new PaymentVerificationRequest(
				"order_" + bookingId, "pay_" + bookingId, "sig_" + bookingId, null));

		assertThat(eventsFor(events, event)).hasSize(1);
		assertThat(eventsFor(events, event).get(0).status()).isEqualTo(SeatStatus.BOOKED);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.BOOKED);
	}

	@Test
	void pendingCancellationPublishesAvailableEvent() {
		User user = newUser("stream-pending-cancel@example.test");
		Event event = newPublishedEvent("Stream Pending Cancel");
		UUID bookingId = reserve(user, event, "S001");
		List<SeatStatusEvent> events = captureLocalBroadcasts();

		bookingService.cancelBooking(bookingId, user.getId());

		assertThat(eventsFor(events, event)).hasSize(1);
		assertThat(eventsFor(events, event).get(0).status()).isEqualTo(SeatStatus.AVAILABLE);
		assertThat(eventsFor(events, event).get(0).seatVersion())
				.isEqualTo(seatRepository.findById(seatId(event, "S001")).orElseThrow().getVersion());
	}

	@Test
	void holdExpirationPublishesAvailableEvent() {
		User user = newUser("stream-expire@example.test");
		Event event = newPublishedEvent("Stream Expire Event");
		UUID bookingId = reserve(user, event, "S001");
		List<SeatStatusEvent> events = captureLocalBroadcasts();

		Booking due = bookingRepository.findById(bookingId).orElseThrow();
		due.setExpiresAt(Instant.now().minusSeconds(60));
		bookingRepository.saveAndFlush(due);

		assertThat(bookingService.expireIfDue(bookingId)).isTrue();

		assertThat(eventsFor(events, event)).hasSize(1);
		assertThat(eventsFor(events, event).get(0).status()).isEqualTo(SeatStatus.AVAILABLE);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.AVAILABLE);
	}

	@Test
	void dueHoldWithBookedSeatIsNeverExpiredAndPublishesNothing() {
		User user = newUser("stream-sale@example.test");
		Event event = newPublishedEvent("Stream Sale Event");
		UUID bookingId = reserve(user, event, "S001");
		List<SeatStatusEvent> events = captureLocalBroadcasts();

		Booking due = bookingRepository.findById(bookingId).orElseThrow();
		due.setExpiresAt(Instant.now().minusSeconds(60));
		bookingRepository.saveAndFlush(due);
		Seat sold = seatRepository.findById(seatId(event, "S001")).orElseThrow();
		sold.setStatus(SeatStatus.BOOKED);
		seatRepository.saveAndFlush(sold);

		assertThat(bookingService.expireIfDue(bookingId)).isFalse();

		// The sale owns the seat: no release, and no false AVAILABLE event.
		assertThat(eventsFor(events, event)).isEmpty();
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.BOOKED);
	}

	@Test
	void confirmedCancellationPublishesAvailableEvent() {
		User user = newUser("stream-refund@example.test");
		Event event = newPublishedEvent("Stream Refund Event");
		UUID bookingId = reserve(user, event, "S001");
		attachOrderPayment(bookingId, event);
		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		booking.setStatus(BookingStatus.CONFIRMED);
		bookingRepository.saveAndFlush(booking);
		Seat sold = booking.getSeat();
		sold.setStatus(SeatStatus.BOOKED);
		seatRepository.saveAndFlush(sold);
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		payment.setRazorpayPaymentId("pay_" + bookingId);
		payment.setStatus(com.soubhagya.flashreserve.entity.enums.PaymentStatus.SUCCESS);
		paymentRepository.saveAndFlush(payment);
		List<SeatStatusEvent> events = captureLocalBroadcasts();

		given(paymentProvider.refundPayment(anyString(), any(BigDecimal.class))).willReturn("rfnd_stream_1");
		bookingService.cancelBooking(bookingId, user.getId());

		assertThat(eventsFor(events, event)).hasSize(1);
		assertThat(eventsFor(events, event).get(0).status()).isEqualTo(SeatStatus.AVAILABLE);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.AVAILABLE);
	}

	@Test
	void failedRefundKeepsSeatBookedAndPublishesNothing() {
		User user = newUser("stream-refund-fail@example.test");
		Event event = newPublishedEvent("Stream Refund Fail");
		UUID bookingId = reserve(user, event, "S001");
		attachOrderPayment(bookingId, event);
		Booking booking = bookingRepository.findById(bookingId).orElseThrow();
		booking.setStatus(BookingStatus.CONFIRMED);
		bookingRepository.saveAndFlush(booking);
		Seat sold = booking.getSeat();
		sold.setStatus(SeatStatus.BOOKED);
		seatRepository.saveAndFlush(sold);
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		payment.setRazorpayPaymentId("pay_" + bookingId);
		payment.setStatus(com.soubhagya.flashreserve.entity.enums.PaymentStatus.SUCCESS);
		paymentRepository.saveAndFlush(payment);
		List<SeatStatusEvent> events = captureLocalBroadcasts();

		willThrow(new RuntimeException("provider down")).given(paymentProvider)
				.refundPayment(anyString(), any(BigDecimal.class));

		assertThatThrownBy(() -> bookingService.cancelBooking(bookingId, user.getId()))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("provider down");

		assertThat(eventsFor(events, event)).isEmpty();
		assertThat(bookingRepository.findById(bookingId).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
		assertThat(seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus())
				.isEqualTo(SeatStatus.BOOKED);
	}

	@Test
	void expiryRacingAWinningPaymentNeverPublishesFalseAvailable() throws Exception {
		User user = newUser("stream-expire-pay@example.test");
		Event event = newPublishedEvent("Stream Expire Pay");
		UUID bookingId = reserve(user, event, "S001");
		attachOrderPayment(bookingId, event);

		// Make the hold due so the sweeper and the checkout confirmation
		// genuinely race on the same seat row (optimistic lock arbitrates).
		Booking due = bookingRepository.findById(bookingId).orElseThrow();
		due.setExpiresAt(Instant.now().minusSeconds(60));
		bookingRepository.saveAndFlush(due);

		List<SeatStatusEvent> events = captureLocalBroadcasts();
		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch startGate = new CountDownLatch(1);
		var confirmFuture = pool.submit((Callable<Object>) () -> {
			startGate.await();
			try {
				return paymentService.verify(bookingId, user.getId(), new PaymentVerificationRequest(
						"order_" + bookingId, "pay_" + bookingId, "sig_" + bookingId, null));
			}
			catch (Throwable failure) {
				return failure;
			}
		});
		var sweepFuture = pool.submit((Callable<Object>) () -> {
			startGate.await();
			try {
				return bookingService.expireIfDue(bookingId);
			}
			catch (Throwable failure) {
				return failure;
			}
		});
		startGate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
		assertThat(confirmFuture.get()).isNotNull();
		assertThat(sweepFuture.get()).isNotNull();

		SeatStatus finalStatus = seatRepository.findById(seatId(event, "S001")).orElseThrow().getStatus();
		boolean seatAvailable = finalStatus == SeatStatus.AVAILABLE;
		long availableEvents = eventsFor(events, event).stream()
				.filter(candidate -> candidate.status() == SeatStatus.AVAILABLE).count();
		// The published events must always agree with the committed state.
		assertThat(availableEvents).isEqualTo(seatAvailable ? 1 : 0);
	}

	@Test
	void redisPubSubOutageDoesNotAffectReservationCommit() {
		User user = newUser("stream-redis-out@example.test");
		Event event = newPublishedEvent("Stream Redis Out");
		UUID seat = seatId(event, "S001");

		// Simulate Redis being unreachable for the seat-update notification
		// only: the publish must be swallowed and the transaction must commit.
		willThrow(new RedisException("pub/sub down")).given(seatUpdatesTopic).publishAsync(anyString());

		ReservationResponse response = bookingService.reserve(user.getId(), event.getId(), seat);

		assertThat(response.bookingId()).isNotNull();
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
	}

	@Test
	void ownRedisEchoIsSuppressedButForeignEchoesFanOut() throws Exception {
		User user = newUser("stream-echo@example.test");
		Event event = newPublishedEvent("Stream Echo Event");
		UUID seat = seatId(event, "S001");
		List<SeatStatusEvent> events = captureLocalBroadcasts();

		SeatStatusEvent held = new SeatStatusEvent(event.getId(), seat, "S001", SeatStatus.HELD, 7L,
				Instant.now());
		seatStatusPublisher.handleInbound(objectMapper.writeValueAsString(
				new SeatStatusPublisher.SeatStreamEnvelope(identity.instanceId(), held)));
		seatStatusPublisher.handleInbound(objectMapper.writeValueAsString(
				new SeatStatusPublisher.SeatStreamEnvelope("other-instance", held)));

		assertThat(eventsFor(events, event)).hasSize(1);
	}

	@Test
	void streamIsScopedToItsOwnEvent() {
		User user = newUser("stream-scoped@example.test");
		Event eventA = newPublishedEvent("Stream Scope A");
		Event eventB = newPublishedEvent("Stream Scope B");
		List<SeatStatusEvent> events = captureLocalBroadcasts();

		bookingService.reserve(user.getId(), eventA.getId(), seatId(eventA, "S001"));

		assertThat(eventsFor(events, eventA)).hasSize(1);
		assertThat(eventsFor(events, eventB)).isEmpty();
	}

}
