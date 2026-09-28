package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Past-event visibility: a PUBLISHED event is public only while
 * {@code startDateTime > now}. Past published events disappear from the
 * public catalog/detail/seats, can never be reserved, but stay visible
 * to ADMIN with history untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000"
})
class PastEventVisibilityIntegrationTests {

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

	private Event savePublishedEvent(String name, Instant eventDate, int seats) {
		Event event = new Event(name, "Past-visibility fixture", "Venue", eventDate, seats);
		event.setStatus(EventStatus.PUBLISHED);
		event = eventRepository.saveAndFlush(event);
		List<Seat> seatRows = new ArrayList<>();
		for (int i = 1; i <= seats; i++) {
			seatRows.add(new Seat(event, String.format("S%03d", i)));
		}
		seatRepository.saveAll(seatRows);
		return event;
	}

	private String adminToken() {
		User admin = userRepository.save(new User("Admin", "past-admin@example.test",
				passwordEncoder.encode("admin-password-123"), UserRole.ADMIN));
		return jwtService.generateToken(admin);
	}

	private String userToken() {
		User user = userRepository.save(new User("User", "past-user@example.test",
				passwordEncoder.encode("password-123"), UserRole.USER));
		return jwtService.generateToken(user);
	}

	@Test
	void futurePublishedEventAppearsInPublicListing() throws Exception {
		Event future = savePublishedEvent("Future Headliner", Instant.now().plusSeconds(86_400), 3);

		String body = mockMvc.perform(get("/api/events").param("size", "100"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> ids = com.jayway.jsonpath.JsonPath.read(body, "$.content[*].id");
		assertThat(ids).contains(future.getId().toString());
	}

	@Test
	void pastPublishedEventDoesNotAppearInPublicListing() throws Exception {
		Event past = savePublishedEvent("Sep 12 Relic", Instant.now().minusSeconds(16 * 86_400), 3);
		Event future = savePublishedEvent("Future Headliner", Instant.now().plusSeconds(86_400), 3);

		String body = mockMvc.perform(get("/api/events").param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].status").exists())
				.andReturn().getResponse().getContentAsString();
		List<String> ids = com.jayway.jsonpath.JsonPath.read(body, "$.content[*].id");
		assertThat(ids).contains(future.getId().toString());
		assertThat(ids).doesNotContain(past.getId().toString());
	}

	@Test
	void adminCanStillSeePastEvent() throws Exception {
		Event past = savePublishedEvent("Sep 12 Relic", Instant.now().minusSeconds(16 * 86_400), 3);
		String token = adminToken();

		mockMvc.perform(get("/api/admin/events")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.param("size", "100"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[*].id").exists());

		String adminBody = mockMvc.perform(get("/api/admin/events")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.param("size", "100"))
				.andReturn().getResponse().getContentAsString();
		List<String> adminIds = com.jayway.jsonpath.JsonPath.read(adminBody, "$.content[*].id");
		assertThat(adminIds).contains(past.getId().toString());

		mockMvc.perform(get("/api/admin/events/{id}", past.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(past.getId().toString()));
	}

	@Test
	void publicDetailForPastEventIsNotFound() throws Exception {
		Event past = savePublishedEvent("Sep 12 Relic", Instant.now().minusSeconds(16 * 86_400), 3);

		mockMvc.perform(get("/api/events/{id}", past.getId()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Event not found: " + past.getId()));
	}

	@Test
	void publicSeatsForPastEventAreNotFound() throws Exception {
		Event past = savePublishedEvent("Sep 12 Relic", Instant.now().minusSeconds(86_400), 3);

		mockMvc.perform(get("/api/events/{id}/seats", past.getId()))
				.andExpect(status().isNotFound());
	}

	@Test
	void reservationOnPastEventIsRejectedAsConflict() throws Exception {
		Event past = savePublishedEvent("Sep 12 Relic", Instant.now().minusSeconds(3_600), 3);
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(past.getId(), "S001").orElseThrow().getId();

		mockMvc.perform(post("/api/events/{eventId}/seats/{seatId}/reservations",
						past.getId(), seatId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken()))
				.andExpect(status().isConflict());
	}

	@Test
	void reservationOnFutureEventStillWorks() throws Exception {
		Event future = savePublishedEvent("Future Headliner", Instant.now().plusSeconds(86_400), 3);
		UUID seatId = seatRepository.findByEventIdAndSeatNumber(future.getId(), "S001").orElseThrow().getId();

		mockMvc.perform(post("/api/events/{eventId}/seats/{seatId}/reservations",
						future.getId(), seatId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.eventId").value(future.getId().toString()));
	}

	@Test
	void paginationAndSortingOfPublicEventsStillWorkWithPastExcluded() throws Exception {
		savePublishedEvent("Past Relic", Instant.now().minusSeconds(30 * 86_400), 2);
		savePublishedEvent("Sort Early", Instant.parse("2027-01-01T10:00:00Z"), 2);
		savePublishedEvent("Sort Late", Instant.parse("2027-06-01T10:00:00Z"), 2);

		String body = mockMvc.perform(get("/api/events")
						.param("page", "0")
						.param("size", "2")
						.param("sort", "eventDate,desc"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.page.totalElements").isNumber())
				.andReturn().getResponse().getContentAsString();

		List<String> rawDates = com.jayway.jsonpath.JsonPath.read(body, "$.content[*].eventDate");
		List<Instant> dates = rawDates.stream().map(Instant::parse).toList();
		assertThat(dates).isSortedAccordingTo(Comparator.reverseOrder());
		assertThat(dates).allMatch(date -> date.isAfter(Instant.now()));
	}
}
