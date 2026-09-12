package com.soubhagya.flashreserve;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest;
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
import com.soubhagya.flashreserve.payment.PaymentProvider;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.PaymentRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.repository.WebhookEventRepository;
import com.soubhagya.flashreserve.service.BookingService;
import com.soubhagya.flashreserve.service.PaymentService;
import com.soubhagya.flashreserve.service.PaymentTransitions;
import com.soubhagya.flashreserve.service.SeatStatusPublisher;
import com.soubhagya.flashreserve.service.SeatUpdateHub;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000",
		"reservation.hold-duration=15m",
		"razorpay.webhook-secret=test_webhook_secret_1234567890"
})
class RazorpayWebhookAdvancedTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired EventRepository eventRepository;
	@Autowired SeatRepository seatRepository;
	@Autowired BookingRepository bookingRepository;
	@Autowired PaymentRepository paymentRepository;
	@Autowired WebhookEventRepository webhookEventRepository;
	@Autowired BookingService bookingService;
	@Autowired PaymentService paymentService;
	@Autowired PasswordEncoder passwordEncoder;
	@MockitoBean PaymentProvider paymentProvider;
	@MockitoSpyBean PaymentTransitions paymentTransitions;
	@MockitoSpyBean SeatStatusPublisher seatStatusPublisher;
	@MockitoSpyBean SeatUpdateHub seatUpdateHub;

	private final List<UUID> userIds = new ArrayList<>();
	private final List<UUID> eventIds = new ArrayList<>();

	@AfterEach void clean() {
		for (UUID eid : eventIds) {
			bookingRepository.findByEventId(eid).forEach(b -> {
				paymentRepository.findByBookingId(b.getId()).ifPresent(paymentRepository::delete);
				bookingRepository.delete(b);
			});
			webhookEventRepository.findAll().forEach(webhookEventRepository::delete);
			seatRepository.findByEventId(eid).forEach(seatRepository::delete);
			eventRepository.deleteById(eid);
		}
		eventIds.clear();
		userIds.forEach(userRepository::deleteById);
		userIds.clear();
		org.mockito.Mockito.reset(paymentTransitions, seatStatusPublisher, seatUpdateHub);
	}

	private User newUser(String email) {
		User u = userRepository.save(new User("User", email, passwordEncoder.encode("password-123"), UserRole.USER));
		userIds.add(u.getId());
		return u;
	}
	private Event newEvent() {
		Event e = new Event("Adv Event", "d", "Hall", Instant.now().plusSeconds(86400), 3);
		e.setStatus(EventStatus.PUBLISHED);
		e.setTicketPrice(new BigDecimal("499.00"));
		Event s = eventRepository.save(e);
		eventIds.add(s.getId());
		List<Seat> seats = new ArrayList<>();
		for (int i =1;i<=3;i++) seats.add(new Seat(s, String.format("S%03d", i)));
		seatRepository.saveAll(seats);
		return s;
	}
	private UUID seatId(Event e, String n) { return seatRepository.findByEventIdAndSeatNumber(e.getId(), n).orElseThrow().getId(); }
	private UUID reserveAndInitiate(User u, Event e) {
		given(paymentProvider.createOrder(anyString(), any(BigDecimal.class))).willReturn("order_adv_1");
		given(paymentProvider.getClientKeyId()).willReturn("rzp_test");
		given(paymentProvider.getCurrency()).willReturn("INR");
		UUID bid = bookingService.reserve(u.getId(), e.getId(), seatId(e,"S001")).bookingId();
		paymentService.initiate(bid, u.getId());
		Payment p = paymentRepository.findByBookingId(bid).orElseThrow();
		p.setRazorpayOrderId("order_adv_1");
		paymentRepository.saveAndFlush(p);
		return bid;
	}
	private String hmac(byte[] body, String s) {
		try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(s.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(body)); } catch (Exception ex){ throw new RuntimeException(ex); }
	}
	private String captured(String o,String p){ return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\""+p+"\",\"order_id\":\""+o+"\"}}}}"; }

	@Test void dbFailureRollbackAndRetry() throws Exception {
		User u = newUser("wh-m@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e);
		String body = captured("order_adv_1","pay_m_1");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		org.mockito.Mockito.doThrow(new RuntimeException("DB down")).when(paymentTransitions).confirmByOrderId(anyString(), anyString());
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_m_1").content(body)).andExpect(status().is5xxServerError());
		assertThat(webhookEventRepository.findByRazorpayEventId("evt_m_1")).isEmpty();
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
		org.mockito.Mockito.reset(paymentTransitions);
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_m_1").content(body)).andExpect(status().isOk());
		assertThat(webhookEventRepository.findByRazorpayEventId("evt_m_1")).isPresent();
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
	}
	@Test void concurrentWebhookAndVerifyPreservesInvariant() throws Exception {
		User u = newUser("wh-o@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e); UUID seat = seatId(e,"S001");
		String body = captured("order_adv_1","pay_o_1");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch gate = new CountDownLatch(1);
		Future<Object> wf = pool.submit((Callable<Object>)()->{ gate.await(); return mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_o_1").content(body)).andReturn().getResponse().getStatus();});
		Future<Object> vf = pool.submit((Callable<Object>)()->{ gate.await(); try{ paymentService.verify(bid,u.getId(), new PaymentVerificationRequest("order_adv_1","pay_o_1","sig",null)); return 200; } catch(Exception ex){ return 409; }});
		gate.countDown(); pool.shutdown(); assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		assertThat(wf.get()).isNotNull(); assertThat(vf.get()).isNotNull();
		Booking b = bookingRepository.findById(bid).orElseThrow(); Seat s = seatRepository.findById(seat).orElseThrow(); Payment p = paymentRepository.findByBookingId(bid).orElseThrow();
		boolean ok = b.getStatus()==BookingStatus.CONFIRMED && s.getStatus()==SeatStatus.BOOKED && p.getStatus()==PaymentStatus.SUCCESS;
		boolean exp = b.getStatus()==BookingStatus.EXPIRED && s.getStatus()==SeatStatus.AVAILABLE && p.getStatus()==PaymentStatus.FAILED;
		assertThat(ok || b.getStatus()==BookingStatus.CONFIRMED || exp || b.getStatus()==BookingStatus.PENDING).isTrue();
		if (b.getStatus()==BookingStatus.CONFIRMED) assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
		if (b.getStatus()==BookingStatus.EXPIRED) assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
	}
	@Test void sseExactlyOnceForRealZeroForIgnored() throws Exception {
		User u = newUser("wh-p-real@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e);
		String body = captured("order_adv_1","pay_p_1");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_p_1").content(body)).andExpect(status().isOk());
		verify(seatStatusPublisher, times(1)).publishAfterCommit(any());
		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_p_1").content(body)).andExpect(status().isOk());
		verify(seatStatusPublisher, never()).publishAfterCommit(any());
		String body2 = captured("order_adv_1","pay_p_2");
		String sig2 = hmac(body2.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		org.mockito.Mockito.reset(seatStatusPublisher, seatUpdateHub);
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig2).header("X-Razorpay-Event-Id","evt_p_2").content(body2)).andExpect(status().isOk());
		verify(seatStatusPublisher, never()).publishAfterCommit(any());
	}
	@Test void missingEventIdReturns400NoMutation() throws Exception {
		User u = newUser("wh-missing@example.test"); Event e = newEvent(); reserveAndInitiate(u,e);
		String body = captured("order_adv_1","pay_missing");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).content(body)).andExpect(status().isBadRequest());
		assertThat(webhookEventRepository.findAll()).isEmpty();
	}
	@Test void malformedJsonReturns400NoMutation() throws Exception {
		String body = "{ not json";
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_mal_1").content(body)).andExpect(status().isBadRequest());
		assertThat(webhookEventRepository.findByRazorpayEventId("evt_mal_1")).isEmpty();
	}
}
