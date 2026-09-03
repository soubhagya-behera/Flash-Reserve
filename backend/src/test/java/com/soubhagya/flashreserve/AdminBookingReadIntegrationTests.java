package com.soubhagya.flashreserve;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = { "jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000" })
class AdminBookingReadIntegrationTests {

	private static final String ADMIN_BOOKINGS_URL = "/api/admin/bookings";

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
		return tokenFor("admin-bookings@example.test", UserRole.ADMIN);
	}

	private String userToken() {
		return tokenFor("user-bookings@example.test", UserRole.USER);
	}

	private User newBooker(String email) {
		return userRepository.save(new User("Booker " + email, email,
				passwordEncoder.encode("password-123"), UserRole.USER));
	}

	private Event newEvent(String name) {
		Event event = new Event(name, "desc", "Venue", Instant.now().plusSeconds(86_400), 5);
		event.setStatus(EventStatus.PUBLISHED);
		event = eventRepository.save(event);
		List<Seat> seats = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
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

	private Payment newPayment(Booking booking, PaymentStatus status) {
		Payment payment = new Payment(booking, new BigDecimal("499.00"));
		payment.setStatus(status);
		payment.setPaymentReference("pay-ref-" + booking.getId().toString().substring(0, 8));
		if (status == PaymentStatus.SUCCESS) {
			payment.setRazorpayPaymentId("pay_" + booking.getId().toString().substring(0, 8));
		}
		return paymentRepository.saveAndFlush(payment);
	}

	@Test
	void adminListShowsBookingsAcrossAllStatuses() throws Exception {
		String token = adminToken();
		Event event = newEvent("Status Spread");
		User booker = newBooker("spread@example.test");
		// The database already contains committed bookings, so totals are
		// asserted relative to the baseline rather than assuming it is empty.
		long baseTotal = bookingRepository.count();
		newBooking(booker, event, "S001", BookingStatus.PENDING);
		newBooking(booker, event, "S002", BookingStatus.CONFIRMED);
		newBooking(booker, event, "S003", BookingStatus.EXPIRED);
		newBooking(booker, event, "S004", BookingStatus.CANCELLED);
		String body = mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray())
				.andExpect(jsonPath("$.page.totalElements").value(baseTotal + 4))
				.andReturn().getResponse().getContentAsString();
		List<String> statuses = JsonPath.read(body, "$.content[*].status");
		assertThat(statuses).contains("PENDING", "CONFIRMED", "EXPIRED", "CANCELLED");
	}

	@Test
	void adminCanFilterTheListByStatus() throws Exception {
		String token = adminToken();
		Event event = newEvent("Status Filter");
		User booker = newBooker("status-filter@example.test");
		UUID pending = newBooking(booker, event, "S001", BookingStatus.PENDING).getId();
		newBooking(booker, event, "S002", BookingStatus.CONFIRMED);
		newBooking(booker, event, "S003", BookingStatus.CANCELLED);
		String body = mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.param("status", "PENDING")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(body, "$.content[*].bookingId");
		List<String> statuses = JsonPath.read(body, "$.content[*].status");
		assertThat(ids).contains(pending.toString());
		assertThat(statuses).containsOnly("PENDING");
	}

	@Test
	void adminCanFilterTheListByEventId() throws Exception {
		String token = adminToken();
		Event first = newEvent("Event Filter A");
		Event second = newEvent("Event Filter B");
		User booker = newBooker("event-filter@example.test");
		UUID firstBooking = newBooking(booker, first, "S001", BookingStatus.PENDING).getId();
		UUID secondBooking = newBooking(booker, second, "S001", BookingStatus.PENDING).getId();
		String body = mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.param("eventId", first.getId().toString())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(body, "$.content[*].bookingId");
		assertThat(ids).containsExactly(firstBooking.toString())
				.doesNotContain(secondBooking.toString());
	}

	@Test
	void adminCanCombineStatusAndEventIdFilters() throws Exception {
		String token = adminToken();
		Event event = newEvent("Combined Filter");
		Event other = newEvent("Combined Other");
		User booker = newBooker("combined@example.test");
		UUID pendingOnEvent = newBooking(booker, event, "S001", BookingStatus.PENDING).getId();
		newBooking(booker, event, "S002", BookingStatus.CONFIRMED);
		newBooking(booker, other, "S001", BookingStatus.PENDING);
		String body = mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.param("status", "PENDING")
						.param("eventId", event.getId().toString())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> ids = JsonPath.read(body, "$.content[*].bookingId");
		List<String> statuses = JsonPath.read(body, "$.content[*].status");
		assertThat(ids).containsExactly(pendingOnEvent.toString());
		assertThat(statuses).containsOnly("PENDING");
	}

	@Test
	void invalidStatusAndMalformedEventIdAreRejectedAsBadRequest() throws Exception {
		String token = adminToken();
		mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.param("status", "NOT_A_STATUS")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.param("eventId", "not-a-uuid")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest());
	}

	@Test
	void unknownEventIdReturnsAnEmptyPage() throws Exception {
		String token = adminToken();
		mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.param("eventId", UUID.randomUUID().toString())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.page.totalElements").value(0));
	}

	@Test
	void paginationUsesDefaultCreatedAtDescOrdering() throws Exception {
		String token = adminToken();
		Event event = newEvent("Paged");
		User booker = newBooker("paged@example.test");
		long baseTotal = bookingRepository.count();
		UUID oldest = newBooking(booker, event, "S001", BookingStatus.PENDING).getId();
		Thread.sleep(30);
		UUID middle = newBooking(booker, event, "S002", BookingStatus.PENDING).getId();
		Thread.sleep(30);
		UUID newest = newBooking(booker, event, "S003", BookingStatus.PENDING).getId();
		String firstPage = mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.param("page", "0").param("size", "2")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.totalElements").value(baseTotal + 3))
				.andReturn().getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(firstPage, "$.content[*].bookingId"))
				.containsExactly(newest.toString(), middle.toString());

		// Page two continues the descending order with the oldest of the new
		// bookings first, whatever else the baseline page happens to contain.
		String secondPage = mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.param("page", "1").param("size", "2")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isNotEmpty())
				.andReturn().getResponse().getContentAsString();
		assertThat((List<String>) JsonPath.read(secondPage, "$.content[*].bookingId"))
				.first().isEqualTo(oldest.toString());
		// A page index beyond the last one is a normal empty page.
		mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.param("page", String.valueOf((baseTotal + 3) / 2 + 1)).param("size", "2")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	void adminDetailReturnsBookingEventSeatAndBookerInformation() throws Exception {
		String token = adminToken();
		Event event = newEvent("Detail Booking");
		User booker = newBooker("detail-booker@example.test");
		Booking booking = newBooking(booker, event, "S001", BookingStatus.PENDING);
		mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/{id}", booking.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bookingId").value(booking.getId().toString()))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.expiresAt").isNotEmpty())
				.andExpect(jsonPath("$.eventId").value(event.getId().toString()))
				.andExpect(jsonPath("$.eventName").value("Detail Booking"))
				.andExpect(jsonPath("$.venue").value("Venue"))
				.andExpect(jsonPath("$.seatId").isNotEmpty())
				.andExpect(jsonPath("$.seatNumber").value("S001"))
				.andExpect(jsonPath("$.bookerId").value(booker.getId().toString()))
				.andExpect(jsonPath("$.bookerName").value("Booker detail-booker@example.test"))
				.andExpect(jsonPath("$.bookerEmail").value("detail-booker@example.test"));
	}

	@Test
	void adminDetailReturnsPaymentInformationWhenPresent() throws Exception {
		String token = adminToken();
		Event event = newEvent("Paid Booking");
		User booker = newBooker("paid@example.test");
		Booking booking = newBooking(booker, event, "S001", BookingStatus.CONFIRMED);
		Payment payment = newPayment(booking, PaymentStatus.SUCCESS);

		mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/{id}", booking.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payment.paymentReference").value(payment.getPaymentReference()))
				.andExpect(jsonPath("$.payment.amount").value(499.0))
				.andExpect(jsonPath("$.payment.paymentStatus").value("SUCCESS"))
				.andExpect(jsonPath("$.payment.razorpayPaymentId").value(payment.getRazorpayPaymentId()));
	}

	@Test
	void adminDetailReturnsNullPaymentWhenNoPaymentRowExists() throws Exception {
		String token = adminToken();
		Event event = newEvent("Unpaid Booking");
		Booking booking = newBooking(newBooker("unpaid@example.test"), event, "S001",
				BookingStatus.PENDING);
		mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/{id}", booking.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.payment").value(nullValue()));
	}

	@Test
	void adminDetailReturnsNotFoundForUnknownAndBadRequestForMalformedId() throws Exception {
		String token = adminToken();
		UUID missing = UUID.randomUUID();
		mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/{id}", missing)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Booking not found: " + missing));
		mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/not-a-uuid")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest());
	}

	@Test
	void anonymousAndUserRequestsAreRejectedOnAdminBookingReads() throws Exception {
		String user = userToken();
		String admin = adminToken();

		mockMvc.perform(get(ADMIN_BOOKINGS_URL)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/{id}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
				.andExpect(status().isForbidden());
		mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/{id}", UUID.randomUUID())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + user))
				.andExpect(status().isForbidden());
		// Admin cannot use the owner-scoped booking endpoints either.
		mockMvc.perform(get("/api/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/bookings/{id}", UUID.randomUUID())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
				.andExpect(status().isForbidden());
	}

	@Test
	void adminBookingResponsesNeverExposeSecretMaterial() throws Exception {
		String token = adminToken();
		Event event = newEvent("Hidden Check");
		Booking booking = newBooking(newBooker("hidden-check@example.test"), event, "S001",
				BookingStatus.CONFIRMED);
		newPayment(booking, PaymentStatus.SUCCESS);
		String detail = mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/{id}", booking.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		String list = mockMvc.perform(get(ADMIN_BOOKINGS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(detail).contains("hidden-check@example.test")
				.doesNotContain("password", "secret", "jwt", "Authorization",
						"razorpayOrderId", "rzp_test");
		assertThat(list).doesNotContain("password", "secret", "jwt", "Authorization",
				"razorpayOrderId", "rzp_test");
	}

	@Test
	void adminBookingReadsDoNotMutateBookingPaymentOrSeatState() throws Exception {
		String token = adminToken();
		Event event = newEvent("Read Only");
		User booker = newBooker("read-only@example.test");
		Booking pending = newBooking(booker, event, "S001", BookingStatus.PENDING);
		Booking confirmed = newBooking(booker, event, "S002", BookingStatus.CONFIRMED);
		Payment pendingPayment = newPayment(pending, PaymentStatus.PENDING);
		newPayment(confirmed, PaymentStatus.SUCCESS);
		mockMvc.perform(get(ADMIN_BOOKINGS_URL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
		mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/{id}", pending.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
		mockMvc.perform(get(ADMIN_BOOKINGS_URL + "/{id}", confirmed.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
		assertThat(bookingRepository.findById(pending.getId()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.PENDING);
		assertThat(bookingRepository.findById(confirmed.getId()).orElseThrow().getStatus())
				.isEqualTo(BookingStatus.CONFIRMED);
		assertThat(paymentRepository.findById(pendingPayment.getId()).orElseThrow().getStatus())
				.isEqualTo(PaymentStatus.PENDING);
		assertThat(seatRepository.findByEventIdAndSeatNumber(event.getId(), "S001")
				.orElseThrow().getStatus()).isEqualTo(SeatStatus.HELD);
		assertThat(seatRepository.findByEventIdAndSeatNumber(event.getId(), "S002")
				.orElseThrow().getStatus()).isEqualTo(SeatStatus.BOOKED);
	}

}