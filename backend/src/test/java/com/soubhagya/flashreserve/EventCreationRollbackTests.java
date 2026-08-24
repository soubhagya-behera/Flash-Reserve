package com.soubhagya.flashreserve;

import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.security.JwtService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import org.springframework.security.crypto.password.PasswordEncoder;

import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000"
})
class EventCreationRollbackTests {

	private static final String ADMIN_EMAIL = "rollback-admin@example.test";

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

	@MockitoBean
	private SeatRepository seatRepository;

	@AfterEach
	void cleanUp() {
		userRepository.findByEmail(ADMIN_EMAIL).ifPresent(userRepository::delete);
	}

	private String adminToken() {
		User admin = userRepository.save(new User("Admin", ADMIN_EMAIL,
				passwordEncoder.encode("admin-password-123"), UserRole.ADMIN));
		return jwtService.generateToken(admin);
	}

	@Test
	void failedSeatInventoryCreationRollsBackEventCompletely() throws Exception {
		given(seatRepository.saveAll(any())).willThrow(new RuntimeException("simulated inventory failure"));

		mockMvc.perform(post("/api/admin/events")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Rollback Event","description":"d","venue":"Hall",
								 "eventDate":"2027-06-01T18:00:00Z","totalSeats":10}"""))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.message").value("Unexpected internal error"))
				.andExpect(jsonPath("$.status").value(500));

		assertThat(eventRepository.count()).as("no event row may survive a failed inventory creation").isZero();
		assertThat(eventRepository.findAll()).isEmpty();
	}

}
