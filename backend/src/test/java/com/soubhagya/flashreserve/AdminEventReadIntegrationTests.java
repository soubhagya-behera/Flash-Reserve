package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.repository.EventRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000"
})
class AdminEventReadIntegrationTests {

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
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	private String tokenFor(String email, UserRole role) {
		User user = userRepository.save(new User("Test " + role, email,
				passwordEncoder.encode("password-123"), role));
		return jwtService.generateToken(user);
	}

	private String adminToken() {
		return tokenFor("admin-read@example.test", UserRole.ADMIN);
	}

	private String userToken() {
		return tokenFor("user-read@example.test", UserRole.USER);
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

	private UUID saveEvent(EventStatus status) {
		Event event = new Event("Status-" + status, "d", "Venue",
				Instant.now().plusSeconds(86_400), 5);
		event.setStatus(status);
		return eventRepository.saveAndFlush(event).getId();
	}
@Test
	void adminCanListEveryEventRegardlessOfStatus() throws Exception {
		String token = adminToken();
		// The database already contains committed events from earlier sessions,
		// so totals are asserted relative to the baseline like the rest of the
		// suite rather than assuming a clean database.
		long baseTotal = eventRepository.count();
		saveEvent(EventStatus.DRAFT);
		saveEvent(EventStatus.PUBLISHED);
		saveEvent(EventStatus.CANCELLED);
		saveEvent(EventStatus.COMPLETED);

		String body = mockMvc.perform(get(ADMIN_EVENTS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray())
				.andExpect(jsonPath("$.page.totalElements").value(baseTotal + 4))
				.andReturn().getResponse().getContentAsString();

		List<String> statuses = com.jayway.jsonpath.JsonPath.read(body, "$.content[*].status");
		assertThat(statuses)
				.contains("DRAFT", "PUBLISHED", "CANCELLED", "COMPLETED");
	}

	@Test
	void adminCanFilterTheListByStatus() throws Exception {
		String token = adminToken();
		UUID draftA = saveEvent(EventStatus.DRAFT);
		UUID draftB = saveEvent(EventStatus.DRAFT);
		UUID published = saveEvent(EventStatus.PUBLISHED);
		saveEvent(EventStatus.CANCELLED);
		saveEvent(EventStatus.COMPLETED);

		String drafts = mockMvc.perform(get(ADMIN_EVENTS_URL)
						.param("status", "DRAFT")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> draftIds = com.jayway.jsonpath.JsonPath.read(drafts, "$.content[*].id");
		List<String> draftStatuses = com.jayway.jsonpath.JsonPath.read(drafts, "$.content[*].status");
		assertThat(draftIds).contains(draftA.toString(), draftB.toString());
		assertThat(draftStatuses).containsOnly("DRAFT");

		String publishedBody = mockMvc.perform(get(ADMIN_EVENTS_URL)
						.param("status", "PUBLISHED")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		List<String> publishedIds = com.jayway.jsonpath.JsonPath.read(publishedBody, "$.content[*].id");
		List<String> publishedStatuses = com.jayway.jsonpath.JsonPath.read(publishedBody, "$.content[*].status");
		assertThat(publishedIds).contains(published.toString());
		assertThat(publishedStatuses).containsOnly("PUBLISHED");
	}

	@Test
	void adminCanRetrieveDraftEvent() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		mockMvc.perform(get(ADMIN_EVENTS_URL + "/{id}", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(eventId))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.name").value("Spring Concert"))
				.andExpect(jsonPath("$.totalSeats").value(25));
	}

	@Test
	void adminCanRetrievePublishedEvent() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/publish", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get(ADMIN_EVENTS_URL + "/{id}", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(eventId))
				.andExpect(jsonPath("$.status").value("PUBLISHED"));
	}

	@Test
	void adminCanRetrieveCancelledEvent() throws Exception {
		String token = adminToken();
		String eventId = createEvent(token);

		mockMvc.perform(patch(ADMIN_EVENTS_URL + "/{id}/cancel", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());

		mockMvc.perform(get(ADMIN_EVENTS_URL + "/{id}", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(eventId))
				.andExpect(jsonPath("$.status").value("CANCELLED"));
	}

	@Test
	void adminCanRetrieveCompletedEvent() throws Exception {
		String token = adminToken();
		UUID eventId = saveEvent(EventStatus.COMPLETED);

		mockMvc.perform(get(ADMIN_EVENTS_URL + "/{id}", eventId)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(eventId.toString()))
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void userTokenIsForbiddenOnAdminEventReads() throws Exception {
		String token = userToken();

		mockMvc.perform(get(ADMIN_EVENTS_URL)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());

		mockMvc.perform(get(ADMIN_EVENTS_URL + "/{id}", UUID.randomUUID())
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());
	}

	@Test
	void anonymousRequestIsUnauthorizedOnAdminEventReads() throws Exception {
		mockMvc.perform(get(ADMIN_EVENTS_URL))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(get(ADMIN_EVENTS_URL + "/{id}", UUID.randomUUID()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void adminGetsNotFoundForUnknownEventId() throws Exception {
		UUID missing = UUID.randomUUID();

		mockMvc.perform(get(ADMIN_EVENTS_URL + "/{id}", missing)
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Event not found: " + missing));
	}

	@Test
	void invalidStatusFilterAndEventIdAreRejectedAsBadRequest() throws Exception {
		String token = adminToken();

		mockMvc.perform(get(ADMIN_EVENTS_URL)
						.param("status", "NOT_A_STATUS")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get(ADMIN_EVENTS_URL + "/not-a-uuid")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isBadRequest());
	}

}