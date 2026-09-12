package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RedissonClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RL-01: default/direct deployment must ignore spoofed forwarding headers.
 * Identity is request.getRemoteAddr() (TCP peer); Tomcat native handling is
 * opt-in via trusted-proxies and is NOT exercised by MockMvc here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m",
		"reservation.rate-limit.capacity=3",
		"reservation.rate-limit.refill-period=10m",
		"auth.login.capacity=3",
		"auth.login.refill-period=10m",
		"auth.registration.capacity=3",
		"auth.registration.refill-period=10m"
})
class RateLimitProxyIdentityIntegrationTests {
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
	@Autowired
	private RedissonClient redissonClient;
	private String randomClientIp() {
		ThreadLocalRandom r = ThreadLocalRandom.current();
		return "10." + r.nextInt(0, 256) + "." + r.nextInt(0, 256) + "." + r.nextInt(2, 255);
	}
	private RequestPostProcessor fromPeer(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}
	@Test
	void spoofedForwardedHeadersAreIgnoredByDefault() throws Exception {
		String peer = randomClientIp();
		String spoof = "203.0.113." + ThreadLocalRandom.current().nextInt(2, 255);
		for (int i = 0; i < 3; i++) {
			assertThat(attemptLogin(peer, i == 1 ? spoof : null))
					.as("pre-limit attempts reach credential checking")
					.isEqualTo(HttpStatus.UNAUTHORIZED.value());
		}
		mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"rl-proxy@example.test\",\"password\":\"wrong-password\"}")
				.with(fromPeerWithSpoof(peer, spoof)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "600"))
				.andExpect(jsonPath("$.message").value("Too many authentication attempts. Please try again shortly."))
				.andExpect(jsonPath("$.status").value(429));
	}
	@Test
	void differentTcpPeersHaveIndependentBuckets() throws Exception {
		String flooder = randomClientIp();
		String newcomer = randomClientIp();
		for (int i = 0; i < 3; i++) {
			attemptLogin(flooder, null);
		}
		assertThat(attemptLogin(flooder, null)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
		assertThat(attemptLogin(newcomer, flooder))
				.as("spoofing an exhausted IP grants no identity; newcomer peer still has budget")
				.isEqualTo(HttpStatus.UNAUTHORIZED.value());
	}
	@Test
	void registrationScopeIsIndependentAndRetryAfterPreserved() throws Exception {
		String peer = randomClientIp();
		String tag = String.valueOf(Math.abs(peer.hashCode()));
		for (int i = 0; i < 3; i++) {
			attemptLogin(peer, null);
		}
		assertThat(attemptLogin(peer, null)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
		String email = "rl-proxy-reg-" + tag + "@gmail.com";
		redissonClient.getBucket("flashreserve:otp:verified:" + email.toLowerCase())
				.set(email.toLowerCase(), 10, TimeUnit.MINUTES);
		MvcResult result = mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Proxy User\",\"email\":\"" + email + "\",\"password\":\"password-123\"}")
				.with(fromPeer(peer)))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.CREATED.value());
	}
	@Test
	void reservationRemainsUserBasedDespiteSpoofedHeaders() throws Exception {
		User user = userRepository.save(new User("Proxy User", "rl-proxy-res@example.test",
				passwordEncoder.encode("password-123"), UserRole.USER));
		User other = userRepository.save(new User("Proxy Other", "rl-proxy-other@example.test",
				passwordEncoder.encode("password-123"), UserRole.USER));
		Event event = new Event("Proxy Event", "d", "Hall", Instant.now().plusSeconds(86_400), 1);
		event.setStatus(EventStatus.PUBLISHED);
		event = eventRepository.save(event);
		Seat seat = seatRepository.save(new Seat(event, "S001"));
		UUID eventId = event.getId();
		UUID seatId = seat.getId();
		String url = "/api/events/" + eventId + "/seats/" + seatId + "/reservations";
		String token = jwtService.generateToken(user);
		for (int i = 0; i < 3; i++) {
			MvcResult pre = mockMvc.perform(post(url)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
					.header("X-Forwarded-For", "203.0.113." + i))
					.andReturn();
			assertThat(pre.getResponse().getStatus()).as("pre-limit reserve passes limiter").isIn(201, 409);
		}
		mockMvc.perform(post(url)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
				.header("X-Forwarded-For", "198.51.100.9"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string(HttpHeaders.RETRY_AFTER, "600"))
				.andExpect(jsonPath("$.message").value("Too many reservation requests. Please try again shortly."));
		Event fresh = new Event("Proxy Fresh", "d", "Hall", Instant.now().plusSeconds(86_400), 1);
		fresh.setStatus(EventStatus.PUBLISHED);
		fresh = eventRepository.save(fresh);
		Seat freshSeat = seatRepository.save(new Seat(fresh, "S001"));
		mockMvc.perform(post("/api/events/" + fresh.getId() + "/seats/" + freshSeat.getId() + "/reservations")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.generateToken(other)))
				.andExpect(status().isCreated());
	}

	private RequestPostProcessor fromPeerWithSpoof(String ip, String spoof) {
		return request -> {
			request.setRemoteAddr(ip);
			request.addHeader("X-Forwarded-For", spoof);
			request.addHeader("Forwarded", "for=" + spoof);
			return request;
		};
	}
	private int attemptLogin(String ip, String spoof) throws Exception {
		String body = "{\"email\":\"rl-proxy@example.test\",\"password\":\"wrong-password\"}";
		MvcResult result = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)
				.with(spoof == null ? fromPeer(ip) : fromPeerWithSpoof(ip, spoof)))
				.andReturn();
		return result.getResponse().getStatus();
	}
}
