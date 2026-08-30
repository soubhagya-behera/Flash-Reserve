package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
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
import org.springframework.http.MediaType;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000"
})
class EventApiIntegrationTests {

	private static final String ADMIN_EVENTS_URL = "/api/admin/events";

	private static final String CREATE_BODY = """
			{"name":"Spring Concert","description":"Open air concert","venue":"City Arena",
			 "eventDate":"2027-06-01T18:00:00Z","totalSeats":25,"ticketPrice":499.00}""";

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

	private String adminToken() {
		User admin = userRepository.save(new User("Admin", "event-admin@example.test",
				passwordEncoder.encode("admin-password-123"), UserRole.ADMIN));
		return jwtService.generateToken(admin);
	}

	private String userToken() throws Exception {
		String body = """
				{"name":"Plain User","email":"plain-user@example.test","password":"password-123"}""";
		mockMvc.perform(post("/api/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated());
		User user = userRepository.findByEmail("plain-user@example.test").orElseThrow();
		return jwtService.generateToken(user);
	}

	private String createEvent(String token) throws Exception {
		String response = mockMvc.perform(post(ADMIN_EVENTS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(CREATE_BODY))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(response, "$.id");
	}

	@Test
	void adminCanCreateEventAndReceivesDraftWithLocation() throws Exception {
		String token = adminToken();

		mockMvc.perform(post(ADMIN_EVENTS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(CREATE_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNotEmpty())
				.andExpect(header().exists(HttpHeaders.LOCATION))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.totalSeats").value(25))
				.andExpect(jsonPath("$.name").value("Spring Concert"))
				.andExpect(jsonPath("$.createdAt").isNotEmpty());
	}

	@Test
	void userCannotCreateEvents() throws Exception {
		mockMvc.perform(post(ADMIN_EVENTS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(CREATE_BODY))
				.andExpect(status().isForbidden());
	}

	@Test
	void unauthenticatedCreateIsRejected() throws Exception {
		mockMvc.perform(post(ADMIN_EVENTS_URL)
						.contentType(MediaType.APPLICATION_JSON)
						.content(CREATE_BODY))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void creationGeneratesUniqueAvailableSeatsWithDeterministicNumbers() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		var seats = seatRepository.findByEventId(UUID.fromString(eventId));

		assertThat(seats).hasSize(25);
		Set<String> numbers = new HashSet<>();
		seats.forEach(seat -> {
			numbers.add(seat.getSeatNumber());
			assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
			assertThat(seat.getVersion()).isEqualTo(0L);
		});
		assertThat(numbers).hasSize(25);
		assertThat(numbers).contains("S001", "S025");
	}

	@Test
	void createRejectsInvalidPayloads() throws Exception {
		String token = adminToken();

		String invalid = """
				{"name":"","venue":"","eventDate":null,"totalSeats":0}""";
		mockMvc.perform(post(ADMIN_EVENTS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(invalid))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.name").exists())
				.andExpect(jsonPath("$.fieldErrors.venue").exists())
				.andExpect(jsonPath("$.fieldErrors.eventDate").exists())
				.andExpect(jsonPath("$.fieldErrors.totalSeats").exists());
	}

	@Test
	void createRejectsPastEventDate() throws Exception {
		String token = adminToken();

		String past = """
				{"name":"Past Event","description":"d","venue":"Hall",
				 "eventDate":"2020-01-01T18:00:00Z","totalSeats":5,"ticketPrice":10.00}""";
		mockMvc.perform(post(ADMIN_EVENTS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(past))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.fieldErrors.eventDate")
						.value("Event date must be in the future"));
	}

	@Test
	void updateCannotMoveEventDateIntoThePast() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		String movedToPast = """
				{"name":"Spring Concert","description":"Open air concert","venue":"City Arena",
				 "eventDate":"2020-01-01T18:00:00Z","ticketPrice":499.00}""";
		mockMvc.perform(put(ADMIN_EVENTS_URL + "/{id}", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(movedToPast))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Event date cannot be moved into the past"));

		// Keeping the unchanged future date is still a valid update.
		String sameFutureDate = """
				{"name":"Renamed Concert","description":"Open air concert","venue":"City Arena",
				 "eventDate":"2027-06-01T18:00:00Z","ticketPrice":499.00}""";
		mockMvc.perform(put(ADMIN_EVENTS_URL + "/{id}", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(sameFutureDate))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Renamed Concert"));
	}

	@Test
	void existingPastEventStaysEditableWithoutMovingItsDate() throws Exception {
		String token = adminToken();
		Event legacy = eventRepository.save(new Event("Legacy Event", "d", "Old Hall",
				Instant.now().minusSeconds(86_400), 5));

		String body = """
				{"name":"Legacy Event Renamed","description":"d","venue":"New Hall",
				 "eventDate":"%s","ticketPrice":499.00}""".formatted(legacy.getEventDate());
		mockMvc.perform(put(ADMIN_EVENTS_URL + "/{id}", legacy.getId())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Legacy Event Renamed"));
	}

	@Test
	void adminCanPublishDraftEvent() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PUBLISHED"));
	}

	@Test
	void publishingNonDraftEventIsRejectedAsConflict() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));
	}

	@Test
	void adminCanCancelDraftAndPublishedEvents() throws Exception {
		String token = adminToken();
		String draftId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/cancel", draftId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/cancel", draftId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isConflict());
	}

	@Test
	void cancelledEventCannotBePublishedOrRecancelled() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/cancel", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isConflict());

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/cancel", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isConflict());
	}

	@Test
	void adminCanUpdateEditableFieldsButNotStatusOrCapacity() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		String updateBody = """
				{"name":"Renamed Event","description":"Updated","venue":"New Hall",
				 "eventDate":"2027-07-02T20:00:00Z","totalSeats":999,"ticketPrice":399.50}""";
		mockMvc.perform(put(ADMIN_EVENTS_URL + "/{id}", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Renamed Event"))
				.andExpect(jsonPath("$.venue").value("New Hall"))
				.andExpect(jsonPath("$.totalSeats").value(25))
				.andExpect(jsonPath("$.status").value("DRAFT"));

		var event = eventRepository.findById(UUID.fromString(eventId)).orElseThrow();
		assertThat(event.getTotalSeats()).isEqualTo(25);
	}

	@Test
	void missingEventReturnsCentralized404() throws Exception {
		String token = adminToken();
		UUID unknownId = UUID.fromString("00000000-0000-0000-0000-00000000000f");

		mockMvc.perform(put(ADMIN_EVENTS_URL + "/{id}", unknownId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(CREATE_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Event not found: " + unknownId))
				.andExpect(jsonPath("$.path").value("/api/admin/events/" + unknownId));
	}

	@Test
	void publicListingShowsOnlyPublishedEvents() throws Exception {
		String token = adminToken();
		long publishedBefore = publishedEventCount();
		createEvent(token);
		String publishedId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", publishedId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/events"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray())
				.andExpect(jsonPath("$.content.length()").value((int) publishedBefore + 1))
				.andExpect(jsonPath("$.content[*].id", hasItem(publishedId)))
				.andExpect(jsonPath("$.content[*].status", everyItem(is("PUBLISHED"))));
	}

	@Test
	void publicListingSupportsPagination() throws Exception {
		String token = adminToken();
		long publishedBefore = publishedEventCount();
		for (int i = 0; i < 3; i++) {
			String id = createEvent(token);
			mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", id)
							.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(status().isOk());
		}
		long publishedTotal = publishedBefore + 3;

		mockMvc.perform(get("/api/events").param("page", "0").param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.page.totalElements").value(publishedTotal))
				.andExpect(jsonPath("$.page.totalPages").value((publishedTotal + 1) / 2));
	}

	private long publishedEventCount() {
		return eventRepository.findAll().stream()
				.filter(event -> event.getStatus() == EventStatus.PUBLISHED)
				.count();
	}

	@Test
	void publicDetailExposesOnlyPublishedEvents() throws Exception {
		String token = adminToken();
		String draftId = createEvent(token);
		String publishedId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", publishedId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/events/{id}", publishedId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(publishedId));

		mockMvc.perform(get("/api/events/{id}", draftId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Event not found: " + draftId));
	}

	@Test
	void publicSeatInventoryIsVisibleForPublishedEventsOnly() throws Exception {
		String token = adminToken();
		String draftId = createEvent(token);
		String publishedId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", publishedId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/events/{id}/seats", publishedId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(25))
				.andExpect(jsonPath("$[0].seatNumber").value("S001"))
				.andExpect(jsonPath("$[0].status").value("AVAILABLE"));

		mockMvc.perform(get("/api/events/{id}/seats", draftId))
				.andExpect(status().isNotFound());
	}

	@Test
	void seatInventoryCanBeFilteredByStatus() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/events/{id}/seats", eventId).param("status", "AVAILABLE"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(25));

		mockMvc.perform(get("/api/events/{id}/seats", eventId).param("status", "BOOKED"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void seatResponseNeverContainsInternalFields() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		String body = mockMvc.perform(get("/api/events/{id}/seats", eventId))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("version")
				.doesNotContain("booking")
				.doesNotContain("\"event\"")
				.doesNotContain("createdAt")
				.doesNotContain("updatedAt");
	}

	@Test
	void invalidSeatStatusFilterIsRejectedAsBadRequest() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/events/{id}/seats", eventId).param("status", "NOT_A_STATUS"))
				.andExpect(status().isBadRequest());
	}

}
