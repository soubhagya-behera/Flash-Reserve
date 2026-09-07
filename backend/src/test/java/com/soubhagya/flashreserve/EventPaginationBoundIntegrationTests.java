package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.soubhagya.flashreserve.config.PaginationConfig;
import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.repository.EventRepository;
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

import static org.hamcrest.Matchers.lessThanOrEqualTo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public pagination hardening (security finding WEB-02).
 *
 * The {@code PageableHandlerMethodArgumentResolver} is customized by
 * {@link PaginationConfig} so the effective page size never exceeds
 * {@value PaginationConfig#MAX_PAGE_SIZE}. These tests pin the full contract
 * for the public {@code GET /api/events} catalog:
 *   - the default page size (20) and normal requested sizes are unchanged
 *   - the maximum allowed size is honored; anything larger (including
 *     Integer.MAX_VALUE) is clamped to the maximum with a normal 200
 *   - size=0 and negative sizes keep Spring's safe fallback to the default
 *     size (no new 400 responses)
 *   - sorting and the PagedModel response format are unchanged
 * One authenticated listing (admin events) is asserted to document that the
 * bound intentionally applies to every Pageable endpoint.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000"
})
class EventPaginationBoundIntegrationTests {

	private static final String PUBLIC_EVENTS_URL = "/api/events";

	private static final String ADMIN_EVENTS_URL = "/api/admin/events";

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

	private Event publishedEvent(String name, Instant eventDate) {
		Event event = new Event(name, "Pagination hardening fixture", "Venue", eventDate, 5);
		event.setStatus(EventStatus.PUBLISHED);
		return eventRepository.saveAndFlush(event);
	}

	private String adminToken() {
		User admin = userRepository.save(new User("Admin", "pagination-admin@example.test",
				passwordEncoder.encode("admin-password-123"), UserRole.ADMIN));
		return jwtService.generateToken(admin);
	}

	@Test
	void defaultPaginationUnchanged() throws Exception {
		mockMvc.perform(get(PUBLIC_EVENTS_URL))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(20));
	}

	@Test
	void normalRequestedSizeIsHonored() throws Exception {
		mockMvc.perform(get(PUBLIC_EVENTS_URL).param("size", "24"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(24));
	}

	@Test
	void maximumAllowedSizeIsHonored() throws Exception {
		mockMvc.perform(get(PUBLIC_EVENTS_URL)
						.param("size", String.valueOf(PaginationConfig.MAX_PAGE_SIZE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(PaginationConfig.MAX_PAGE_SIZE));
	}

	@Test
	void sizeAboveMaximumIsClamped() throws Exception {
		mockMvc.perform(get(PUBLIC_EVENTS_URL).param("size", "1000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(PaginationConfig.MAX_PAGE_SIZE))
				.andExpect(jsonPath("$.content.length()",
						lessThanOrEqualTo(PaginationConfig.MAX_PAGE_SIZE)));
	}

	@Test
	void integerMaxValueSizeIsClampedSafely() throws Exception {
		mockMvc.perform(get(PUBLIC_EVENTS_URL).param("size", String.valueOf(Integer.MAX_VALUE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(PaginationConfig.MAX_PAGE_SIZE))
				.andExpect(jsonPath("$.content.length()",
						lessThanOrEqualTo(PaginationConfig.MAX_PAGE_SIZE)));
	}

	@Test
	void sizeZeroFallsBackToDefault() throws Exception {
		mockMvc.perform(get(PUBLIC_EVENTS_URL).param("size", "0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(20));
	}

	@Test
	void negativeSizeFallsBackToDefault() throws Exception {
		mockMvc.perform(get(PUBLIC_EVENTS_URL).param("size", "-5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(20));
	}

	@Test
	void sortingStillWorks() throws Exception {
		publishedEvent("Pagination Old", Instant.parse("2027-01-01T10:00:00Z"));
		publishedEvent("Pagination Mid", Instant.parse("2027-03-01T10:00:00Z"));
		publishedEvent("Pagination New", Instant.parse("2027-06-01T10:00:00Z"));

		String body = mockMvc.perform(get(PUBLIC_EVENTS_URL).param("sort", "eventDate,desc"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();

		List<String> rawDates = com.jayway.jsonpath.JsonPath.read(body, "$.content[*].eventDate");
		List<Instant> dates = rawDates.stream().map(Instant::parse).toList();
		assertThat(dates).isSortedAccordingTo(Comparator.reverseOrder());
		assertThat(dates).contains(Instant.parse("2027-06-01T10:00:00Z"),
				Instant.parse("2027-03-01T10:00:00Z"), Instant.parse("2027-01-01T10:00:00Z"));
	}

	@Test
	void responseFormatUnchanged() throws Exception {
		publishedEvent("Pagination Format", Instant.parse("2027-05-01T12:00:00Z"));

		mockMvc.perform(get(PUBLIC_EVENTS_URL).param("size", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray())
				.andExpect(jsonPath("$.page.number").value(0))
				.andExpect(jsonPath("$.page.size").value(1))
				.andExpect(jsonPath("$.page.totalElements").isNumber())
				.andExpect(jsonPath("$.page.totalPages").isNumber())
				.andExpect(jsonPath("$.content[0].id").isNotEmpty())
				.andExpect(jsonPath("$.content[0].name").isNotEmpty())
				.andExpect(jsonPath("$.content[0].description").isNotEmpty())
				.andExpect(jsonPath("$.content[0].venue").isNotEmpty())
				.andExpect(jsonPath("$.content[0].eventDate").isNotEmpty())
				.andExpect(jsonPath("$.content[0].totalSeats").isNumber())
				.andExpect(jsonPath("$.content[0].ticketPrice").exists())
				.andExpect(jsonPath("$.content[0].status").value("PUBLISHED"))
				.andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
	}

	@Test
	void adminListingIsBoundToTheSameGlobalMaximum() throws Exception {
		String token = adminToken();

		mockMvc.perform(get(ADMIN_EVENTS_URL)
						.param("size", "1000")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(PaginationConfig.MAX_PAGE_SIZE));
	}

}

