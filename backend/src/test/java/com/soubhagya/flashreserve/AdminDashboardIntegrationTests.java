package com.soubhagya.flashreserve;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.jayway.jsonpath.JsonPath;
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
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.PaymentRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.security.JwtService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = { "jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000" })
class AdminDashboardIntegrationTests {

	private static final String DASHBOARD_URL = "/api/admin/dashboard";
	private static final List<String> EVENT_STATUSES =
			List.of("published", "draft", "cancelled", "completed");
	private static final List<String> BOOKING_STATUSES =
			List.of("pending", "confirmed", "expired", "cancelled");
	private static final List<String> SEAT_STATUSES =
			List.of("available", "held", "booked");

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
	private PasswordEncoder passwordEncoder;
	@Autowired
	private JwtService jwtService;

	private String tokenFor(String email, UserRole role) {
		User user = userRepository.save(new User("Test " + role, email,
				passwordEncoder.encode("password-123"), role));
		return jwtService.generateToken(user);
	}

	private String adminToken() {
		return tokenFor("admin-dashboard@example.test", UserRole.ADMIN);
	}

	private String userToken() {
		return tokenFor("user-dashboard@example.test", UserRole.USER);
	}

	private User newBooker(String email) {
		return userRepository.save(new User("Booker " + email, email,
				passwordEncoder.encode("password-123"), UserRole.USER));
	}

	private Instant future() {
		return Instant.now().plusSeconds(86_400);
	}

	private Instant past() {
		return Instant.now().minusSeconds(86_400);
	}

	private Event newEvent(String name, int seatCount, EventStatus status, Instant eventDate) {
		Event event = new Event(name, "desc", "Venue", eventDate, seatCount);
		event.setStatus(status);
		event = eventRepository.save(event);
		List<Seat> seats = new ArrayList<>();
		for (int i = 1; i <= seatCount; i++) {
			seats.add(new Seat(event, String.format("S%03d", i)));
		}
		seatRepository.saveAll(seats);
		return event;
	}

	private Booking newBooking(User booker, Event event, String seatNumber, BookingStatus status) {
		Seat seat = seatRepository.findByEventIdAndSeatNumber(event.getId(), seatNumber).orElseThrow();
		seat.setStatus(status == BookingStatus.CONFIRMED ? SeatStatus.BOOKED
				: status == BookingStatus.PENDING ? SeatStatus.HELD : SeatStatus.AVAILABLE);
		seatRepository.saveAndFlush(seat);
		Booking booking = new Booking(booker, event, seat, Instant.now().plusSeconds(900));
		booking.setStatus(status);
		return bookingRepository.saveAndFlush(booking);
	}

	private Payment newPayment(Booking booking, PaymentStatus status, String amount) {
		Payment payment = new Payment(booking, new BigDecimal(amount));
		payment.setStatus(status);
		payment.setPaymentReference("pay-ref-" + UUID.randomUUID().toString().substring(0, 12));
		if (status == PaymentStatus.SUCCESS) {
			payment.setRazorpayPaymentId("pay_" + UUID.randomUUID().toString().substring(0, 8));
		}
		return paymentRepository.saveAndFlush(payment);
	}

	private String performGet(String token) throws Exception {
		return mockMvc.perform(get(DASHBOARD_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private long readLong(String body, String path) {
		return ((Number) JsonPath.read(body, path)).longValue();
	}

	private double readDouble(String body, String path) {
		return ((Number) JsonPath.read(body, path)).doubleValue();
	}

	@Test
	void adminReceivesAStructurallyCompleteSnapshot() throws Exception {
		String body = performGet(adminToken());
		assertThat(JsonPath.<Map<String, Object>>read(body, "$")).containsKeys(
				"events", "bookings", "revenue", "seats", "recentBookings");
		assertThat(JsonPath.<Map<String, Object>>read(body, "$.events")).containsKeys(
				"total", "published", "draft", "cancelled", "completed", "upcomingPublished");
		assertThat(JsonPath.<Map<String, Object>>read(body, "$.bookings")).containsKeys(
				"total", "pending", "confirmed", "expired", "cancelled");
		assertThat(JsonPath.<Map<String, Object>>read(body, "$.revenue")).containsKeys(
				"confirmedPaymentCount", "confirmedRevenue");
		assertThat(JsonPath.<Map<String, Object>>read(body, "$.seats")).containsKeys(
				"total", "available", "held", "booked");
	}

	@Test
	void dashboardRequiresTheAdminRole() throws Exception {
		String user = userToken();
		String admin = adminToken();

		mockMvc.perform(get(DASHBOARD_URL)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(DASHBOARD_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
				.andExpect(status().isForbidden());
		mockMvc.perform(get(DASHBOARD_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
				.andExpect(status().isOk());
	}

	@Test
	void snapshotIsInternallyConsistentAgainstAnyBaseline() throws Exception {
		String body = performGet(adminToken());

		long eventSum = EVENT_STATUSES.stream().mapToLong(
				status -> readLong(body, "$.events." + status)).sum();
		long bookingSum = BOOKING_STATUSES.stream().mapToLong(
				status -> readLong(body, "$.bookings." + status)).sum();
		long seatSum = SEAT_STATUSES.stream().mapToLong(
				status -> readLong(body, "$.seats." + status)).sum();

		assertThat(eventSum).isEqualTo(readLong(body, "$.events.total"));
		assertThat(bookingSum).isEqualTo(readLong(body, "$.bookings.total"));
		assertThat(seatSum).isEqualTo(readLong(body, "$.seats.total"));
		assertThat(readLong(body, "$.revenue.confirmedPaymentCount")).isGreaterThanOrEqualTo(0);
		assertThat(readDouble(body, "$.revenue.confirmedRevenue")).isGreaterThanOrEqualTo(0.0);
		assertThat((List<?>) JsonPath.read(body, "$.recentBookings")).hasSizeLessThanOrEqualTo(5);
	}

	@Test
	void eventStatusCountsIncludeEveryCreatedEvent() throws Exception {
		String token = adminToken();
		String baseline = performGet(token);
		Map<String, Long> base = EVENT_STATUSES.stream().collect(Collectors.toMap(
				status -> status, status -> readLong(baseline, "$.events." + status)));
		long baseTotal = readLong(baseline, "$.events.total");

		newEvent("Draft Event", 2, EventStatus.DRAFT, future());
		newEvent("Published Event", 2, EventStatus.PUBLISHED, future());
		newEvent("Cancelled Event", 2, EventStatus.CANCELLED, future());
		newEvent("Completed Event", 2, EventStatus.COMPLETED, future());

		String body = performGet(token);
		assertThat(readLong(body, "$.events.published")).isEqualTo(base.get("published") + 1);
		assertThat(readLong(body, "$.events.draft")).isEqualTo(base.get("draft") + 1);
		assertThat(readLong(body, "$.events.cancelled")).isEqualTo(base.get("cancelled") + 1);
		assertThat(readLong(body, "$.events.completed")).isEqualTo(base.get("completed") + 1);
		assertThat(readLong(body, "$.events.total")).isEqualTo(baseTotal + 4);
	}

	@Test
	void upcomingPublishedCountsOnlyFuturePublishedEvents() throws Exception {
		String token = adminToken();
		String before = performGet(token);
		long baseUpcoming = readLong(before, "$.events.upcomingPublished");
		long basePublished = readLong(before, "$.events.published");
		long baseDraft = readLong(before, "$.events.draft");

		newEvent("Upcoming Published", 2, EventStatus.PUBLISHED, future());
		newEvent("Past Published", 2, EventStatus.PUBLISHED, past());
		newEvent("Future Draft", 2, EventStatus.DRAFT, future());
		newEvent("Future Cancelled", 2, EventStatus.CANCELLED, future());

		String body = performGet(token);
		assertThat(readLong(body, "$.events.upcomingPublished")).isEqualTo(baseUpcoming + 1);
		assertThat(readLong(body, "$.events.published")).isEqualTo(basePublished + 2);
		assertThat(readLong(body, "$.events.draft")).isEqualTo(baseDraft + 1);
	}

	@Test
	void bookingStatusCountsIncludeEveryCreatedBooking() throws Exception {
		String token = adminToken();
		Event event = newEvent("Booking Spread", 5, EventStatus.PUBLISHED, future());
		User booker = newBooker("booking-spread@example.test");
		String baseline = performGet(token);
		Map<String, Long> base = BOOKING_STATUSES.stream().collect(Collectors.toMap(
				status -> status, status -> readLong(baseline, "$.bookings." + status)));
		long baseTotal = readLong(baseline, "$.bookings.total");

		newBooking(booker, event, "S001", BookingStatus.PENDING);
		newBooking(booker, event, "S002", BookingStatus.CONFIRMED);
		newBooking(booker, event, "S003", BookingStatus.EXPIRED);
		newBooking(booker, event, "S004", BookingStatus.CANCELLED);

		String body = performGet(token);
		assertThat(readLong(body, "$.bookings.pending")).isEqualTo(base.get("pending") + 1);
		assertThat(readLong(body, "$.bookings.confirmed")).isEqualTo(base.get("confirmed") + 1);
		assertThat(readLong(body, "$.bookings.expired")).isEqualTo(base.get("expired") + 1);
		assertThat(readLong(body, "$.bookings.cancelled")).isEqualTo(base.get("cancelled") + 1);
		assertThat(readLong(body, "$.bookings.total")).isEqualTo(baseTotal + 4);
	}

	@Test
	void confirmedRevenueExcludesEveryNonQualifyingPaymentBookingPair() throws Exception {
		String token = adminToken();
		Event event = newEvent("Revenue Rules", 6, EventStatus.PUBLISHED, future());
		event.setTicketPrice(new BigDecimal("100.00"));
		eventRepository.saveAndFlush(event);
		User booker = newBooker("revenue-rules@example.test");

		String before = performGet(token);
		long baseCount = readLong(before, "$.revenue.confirmedPaymentCount");
		double baseRevenue = readDouble(before, "$.revenue.confirmedRevenue");

		// The only qualifying pair: SUCCESS payment on a CONFIRMED booking.
		newPayment(newBooking(booker, event, "S001", BookingStatus.CONFIRMED),
				PaymentStatus.SUCCESS, "499.00");
		// PENDING payment on a CONFIRMED booking is never revenue.
		newPayment(newBooking(booker, event, "S002", BookingStatus.CONFIRMED),
				PaymentStatus.PENDING, "60.00");
		// FAILED payment is never revenue.
		newPayment(newBooking(booker, event, "S003", BookingStatus.PENDING),
				PaymentStatus.FAILED, "90.00");
		// SUCCESS payment on a non-CONFIRMED booking is never revenue.
		newPayment(newBooking(booker, event, "S004", BookingStatus.EXPIRED),
				PaymentStatus.SUCCESS, "70.00");
		newPayment(newBooking(booker, event, "S005", BookingStatus.CANCELLED),
				PaymentStatus.SUCCESS, "80.00");

		String body = performGet(token);
		assertThat(readLong(body, "$.revenue.confirmedPaymentCount")).isEqualTo(baseCount + 1);
		assertThat(readDouble(body, "$.revenue.confirmedRevenue"))
				.isCloseTo(baseRevenue + 499.00, within(0.001));
	}

	@Test
	void revenueIsDerivedFromThePaymentAmountNotTheTicketPrice() throws Exception {
		String token = adminToken();
		Event event = newEvent("Amount Source", 2, EventStatus.PUBLISHED, future());
		event.setTicketPrice(new BigDecimal("55.00"));
		eventRepository.saveAndFlush(event);
		User booker = newBooker("amount-source@example.test");

		String before = performGet(token);
		long baseCount = readLong(before, "$.revenue.confirmedPaymentCount");
		double baseRevenue = readDouble(before, "$.revenue.confirmedRevenue");

		// Payment amount deliberately differs from the event's ticket price.
		newPayment(newBooking(booker, event, "S001", BookingStatus.CONFIRMED),
				PaymentStatus.SUCCESS, "777.77");

		String body = performGet(token);
		assertThat(readLong(body, "$.revenue.confirmedPaymentCount")).isEqualTo(baseCount + 1);
		assertThat(readDouble(body, "$.revenue.confirmedRevenue"))
				.isCloseTo(baseRevenue + 777.77, within(0.001));
	}

	@Test
	void seatStatusCountsReflectTheInventory() throws Exception {
		String token = adminToken();
		String baseline = performGet(token);
		Map<String, Long> base = SEAT_STATUSES.stream().collect(Collectors.toMap(
				status -> status, status -> readLong(baseline, "$.seats." + status)));
		long baseTotal = readLong(baseline, "$.seats.total");

		Event event = newEvent("Seat Spread", 3, EventStatus.PUBLISHED, future());
		seatRepository.findByEventIdAndSeatNumber(event.getId(), "S002").orElseThrow()
				.setStatus(SeatStatus.HELD);
		seatRepository.findByEventIdAndSeatNumber(event.getId(), "S003").orElseThrow()
				.setStatus(SeatStatus.BOOKED);
		seatRepository.saveAll(seatRepository.findByEventId(event.getId()));

		String body = performGet(token);
		assertThat(readLong(body, "$.seats.available")).isEqualTo(base.get("available") + 1);
		assertThat(readLong(body, "$.seats.held")).isEqualTo(base.get("held") + 1);
		assertThat(readLong(body, "$.seats.booked")).isEqualTo(base.get("booked") + 1);
		assertThat(readLong(body, "$.seats.total")).isEqualTo(baseTotal + 3);
	}

	@Test
	void recentBookingsAreNewestFirstCappedAtFiveWithoutSensitiveFields() throws Exception {
		String token = adminToken();
		Event event = newEvent("Recent Bookings", 10, EventStatus.PUBLISHED, future());
		event.setVenue("Grand Hall");
		eventRepository.saveAndFlush(event);
		User booker = newBooker("recent-bookings@example.test");

		List<UUID> created = new ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			created.add(newBooking(booker, event, String.format("S%03d", i),
					BookingStatus.PENDING).getId());
			if (i < 7) {
				Thread.sleep(30);
			}
		}

		String body = performGet(token);
		List<String> ids = JsonPath.read(body, "$.recentBookings[*].bookingId");
		assertThat(ids).hasSize(5);
		// Newest first: the last two created bookings lead the list.
		assertThat(ids.get(0)).isEqualTo(created.get(6).toString());
		assertThat(ids.get(1)).isEqualTo(created.get(5).toString());

		mockMvc.perform(get(DASHBOARD_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.recentBookings[0].eventName").value("Recent Bookings"))
				.andExpect(jsonPath("$.recentBookings[0].venue").value("Grand Hall"))
				.andExpect(jsonPath("$.recentBookings[0].seatNumber").value("S007"))
				.andExpect(jsonPath("$.recentBookings[0].eventId").value(event.getId().toString()))
				.andExpect(jsonPath("$.recentBookings[0].status").value("PENDING"))
				.andExpect(jsonPath("$.recentBookings[0].createdAt").exists())
				.andExpect(jsonPath("$.recentBookings[0].eventDate").exists())
				// No user identity or payment/provider data is ever exposed.
				.andExpect(jsonPath("$.recentBookings[0].bookerId").doesNotExist())
				.andExpect(jsonPath("$.recentBookings[0].bookerName").doesNotExist())
				.andExpect(jsonPath("$.recentBookings[0].bookerEmail").doesNotExist())
				.andExpect(jsonPath("$.recentBookings[0].payment").doesNotExist())
				.andExpect(jsonPath("$.recentBookings[0].paymentReference").doesNotExist())
				.andExpect(jsonPath("$.recentBookings[0].razorpayOrderId").doesNotExist())
				.andExpect(jsonPath("$.recentBookings[0].razorpayPaymentId").doesNotExist())
				.andExpect(jsonPath("$.recentBookings[0].amount").doesNotExist());
	}

	@Test
	void dashboardEndpointDoesNotMutateAnyState() throws Exception {
		String token = adminToken();
		Event event = newEvent("Read Only", 3, EventStatus.PUBLISHED, future());
		User booker = newBooker("read-only@example.test");
		Booking pending = newBooking(booker, event, "S001", BookingStatus.PENDING);
		newPayment(pending, PaymentStatus.PENDING, "250.00");

		long eventsBefore = eventRepository.count();
		long bookingsBefore = bookingRepository.count();
		long paymentsBefore = paymentRepository.count();
		long seatsBefore = seatRepository.count();
		List<SeatStatus> seatStatusesBefore = seatStatusesOf(event);

		performGet(token);

		assertThat(eventRepository.count()).isEqualTo(eventsBefore);
		assertThat(bookingRepository.count()).isEqualTo(bookingsBefore);
		assertThat(paymentRepository.count()).isEqualTo(paymentsBefore);
		assertThat(seatRepository.count()).isEqualTo(seatsBefore);
		assertThat(seatStatusesOf(event)).containsExactlyElementsOf(seatStatusesBefore);
		assertThat(bookingRepository.findById(pending.getId()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.PENDING);
	}

	private List<SeatStatus> seatStatusesOf(Event event) {
		return seatRepository.findByEventId(event.getId()).stream()
				.sorted(Comparator.comparing(Seat::getSeatNumber))
				.map(Seat::getStatus).toList();
	}

}