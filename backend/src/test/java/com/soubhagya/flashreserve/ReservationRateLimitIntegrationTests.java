package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.repository.EventRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the distributed per-user rate limit on the reservation hot path
 * against the real Redis instance. A tiny policy (3 attempts / 10 min) makes
 * exhaustion deterministic; the 10-minute refill window guarantees no permit
 * is restored while a test is running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m",
		"reservation.rate-limit.capacity=3",
		"reservation.rate-limit.refill-period=10m"
})
class ReservationRateLimitIntegrationTests {

	private static final Set<Integer> PRE_LIMIT_OUTCOMES = Set.of(201, 409);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private SeatRepository seatRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private User newUser(String email) {
		return userRepository.save(new User("Ratelimit Test", email,
				passwordEncoder.encode("password-123"), UserRole.USER));
	}

	private String tokenFor(User user) {
		return jwtService.generateToken(user);
	}

	private UUID newPublishedSeat() {
		Event event = new Event("Rate Limit Event", "d", "Hall",
				Instant.now().plusSeconds(86_400), 1);
		event.setStatus(EventStatus.PUBLISHED);
		event = eventRepository.save(event);
		Seat seat = seatRepository.save(new Seat(event, "S001"));
		return seat.getId();
	}

	private String reserveUrl(UUID eventId, UUID seatId) {
		return "/api/events/" + eventId + "/seats/" + seatId + "/reservations";
	}

	private UUID eventIdFor(UUID seatId) {
		return seatRepository.findById(seatId).orElseThrow().getEvent().getId();
	}

	private int reserve(String url, String token) throws Exception {
		MvcResult result = mockMvc.perform(post(url)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andReturn();
		int code = result.getResponse().getStatus();
		assertThat(code).as("pre-limit requests must pass the limiter").isIn(PRE_LIMIT_OUTCOMES);
		return code;
	}

	@Test
	void overLimitRequestsReturn429WithRetryAfterAndSafeBody() throws Exception {
		User user = newUser("rl-exhaust@example.test");
		UUID seatId = newPublishedSeat();
		String url = reserveUrl(eventIdFor(seatId), seatId);

		reserve(url, tokenFor(user));
		reserve(url, tokenFor(user));
		reserve(url, tokenFor(user));

		mockMvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "600"))
				.andExpect(jsonPath("$.message").value("Too many reservation requests. Please try again shortly."))
				.andExpect(jsonPath("$.status").value(429));
	}

	@Test
	void rejectedRequestsAreStoppedBeforeAnyDatabaseOrSeatLockWork() throws Exception {
		User user = newUser("rl-order@example.test");
		UUID seatId = newPublishedSeat();
		String url = reserveUrl(eventIdFor(seatId), seatId);

		for (int i = 0; i < 3; i++) {
			reserve(url, tokenFor(user));
		}

		UUID ghostEvent = UUID.fromString("00000000-0000-0000-0000-00000000beef");
		UUID ghostSeat = UUID.fromString("00000000-0000-0000-0000-00000000feed");
		mockMvc.perform(post(reserveUrl(ghostEvent, ghostSeat))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.message").value("Too many reservation requests. Please try again shortly."));
	}

	@Test
	void separateUsersConsumeSeparateDistributedBuckets() throws Exception {
		User flooder = newUser("rl-flooder@example.test");
		User newcomer = newUser("rl-newcomer@example.test");
		UUID floodSeat = newPublishedSeat();
		UUID freshSeat = newPublishedSeat();

		for (int i = 0; i < 3; i++) {
			reserve(reserveUrl(eventIdFor(floodSeat), floodSeat), tokenFor(flooder));
		}
		mockMvc.perform(post(reserveUrl(eventIdFor(floodSeat), floodSeat))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(flooder)))
				.andExpect(status().isTooManyRequests());

		mockMvc.perform(post(reserveUrl(eventIdFor(freshSeat), freshSeat))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(newcomer)))
				.andExpect(status().isCreated());
	}

	@Test
	void underLimitReservationStillSucceedsEndToEnd() throws Exception {
		User user = newUser("rl-happy@example.test");
		UUID seatId = newPublishedSeat();

		mockMvc.perform(post(reserveUrl(eventIdFor(seatId), seatId))
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(user)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING"));
	}

}
