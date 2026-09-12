package com.soubhagya.flashreserve;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
class RazorpayWebhookBasicTests {

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
	@Autowired ObjectMapper objectMapper;
	@MockitoBean PaymentProvider paymentProvider;

	private final List<UUID> userIds = new ArrayList<>();
	private final List<UUID> eventIds = new ArrayList<>();

	@AfterEach void clean() {
		for (UUID eid : eventIds) {
			bookingRepository.findByEventId(eid).forEach(b -> {
				paymentRepository.findByBookingId(b.getId()).ifPresent(paymentRepository::delete);
				bookingRepository.delete(b);
			});
			webhookEventRepository.findAll().forEach(e -> webhookEventRepository.delete(e));
			seatRepository.findByEventId(eid).forEach(seatRepository::delete);
			eventRepository.deleteById(eid);
		}
		eventIds.clear();
		userIds.forEach(userRepository::deleteById);
		userIds.clear();
	}

	private User newUser(String email) {
		User u = userRepository.save(new User("User", email, passwordEncoder.encode("password-123"), UserRole.USER));
		userIds.add(u.getId());
		return u;
	}
	private Event newEvent() {
		Event e = new Event("Webhook Event", "d", "Hall", Instant.now().plusSeconds(86400), 3);
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
		given(paymentProvider.createOrder(anyString(), any(BigDecimal.class))).willReturn("order_webhook_1");
		given(paymentProvider.getClientKeyId()).willReturn("rzp_test");
		given(paymentProvider.getCurrency()).willReturn("INR");
		UUID bid = bookingService.reserve(u.getId(), e.getId(), seatId(e,"S001")).bookingId();
		paymentService.initiate(bid, u.getId());
		Payment p = paymentRepository.findByBookingId(bid).orElseThrow();
		p.setRazorpayOrderId("order_webhook_1");
		paymentRepository.saveAndFlush(p);
		return bid;
	}
	private String hmac(byte[] body, String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(body));
		} catch (Exception ex) { throw new RuntimeException(ex); }
	}
	private String capturedPayload(String orderId, String paymentId) {
		return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\""+paymentId+"\",\"order_id\":\""+orderId+"\"}}}}";
	}
	private String failedPayload(String orderId, String paymentId) {
		return "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\""+paymentId+"\",\"order_id\":\""+orderId+"\"}}}}";
	}
	private String refundPayload(String refundId, String paymentId, String orderId) {
		return "{\"event\":\"refund.created\",\"payload\":{\"refund\":{\"entity\":{\"id\":\""+refundId+"\",\"payment_id\":\""+paymentId+"\"}},\"payment\":{\"entity\":{\"id\":\""+paymentId+"\",\"order_id\":\""+orderId+"\"}}}}";
	}

	@Test void validSignatureAccepted() throws Exception {
		User u = newUser("wh-a@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e);
		String body = capturedPayload("order_webhook_1","pay_wh_1");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_a_1").content(body)).andExpect(status().isOk());
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
	}
	@Test void invalidSignatureReturns401NoMutation() throws Exception {
		User u = newUser("wh-b@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e);
		String body = capturedPayload("order_webhook_1","pay_wh_1");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature","invalid").header("X-Razorpay-Event-Id","evt_b_1").content(body)).andExpect(status().isUnauthorized());
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
		assertThat(webhookEventRepository.findByRazorpayEventId("evt_b_1")).isEmpty();
	}
	@Test void paymentCapturedTransitions() throws Exception {
		User u = newUser("wh-c@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e); UUID seat = seatId(e,"S001");
		String body = capturedPayload("order_webhook_1","pay_wh_c");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_c_1").content(body)).andExpect(status().isOk());
		assertThat(bookingRepository.findById(bid).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.BOOKED);
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
		assertThat(webhookEventRepository.findByRazorpayEventId("evt_c_1").orElseThrow().getStatus().name()).isEqualTo("PROCESSED");
	}
	@Test void duplicatePaymentCapturedNoDuplicateTransition() throws Exception {
		User u = newUser("wh-d@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e);
		String body = capturedPayload("order_webhook_1","pay_wh_d");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_d_dup").content(body)).andExpect(status().isOk());
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_d_dup").content(body)).andExpect(status().isOk());
		assertThat(webhookEventRepository.findByRazorpayEventId("evt_d_dup")).isPresent();
		assertThat(webhookEventRepository.findAll().stream().filter(w->w.getRazorpayEventId().equals("evt_d_dup")).count()).isEqualTo(1);
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
	}
	@Test void webhookBeforeVerifyThenVerifyIdempotent() throws Exception {
		User u = newUser("wh-e@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e);
		String body = capturedPayload("order_webhook_1","pay_wh_e");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_e_1").content(body)).andExpect(status().isOk());
		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);
		var req = new com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest("order_webhook_1","pay_wh_e","sig",null);
		var resp = paymentService.verify(bid, u.getId(), req);
		assertThat(resp.paymentStatus()).isEqualTo(PaymentStatus.SUCCESS);
	}
	@Test void verifyBeforeWebhookThenWebhookIgnored() throws Exception {
		User u = newUser("wh-f@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e);
		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);
		paymentService.verify(bid, u.getId(), new com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest("order_webhook_1","pay_wh_f","sig",null));
		String body = capturedPayload("order_webhook_1","pay_wh_f2");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_f_1").content(body)).andExpect(status().isOk());
		assertThat(webhookEventRepository.findByRazorpayEventId("evt_f_1").orElseThrow().getStatus().name()).isEqualTo("IGNORED");
	}
	@Test void paymentFailedTransitions() throws Exception {
		User u = newUser("wh-g@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e); UUID seat = seatId(e,"S001");
		String body = failedPayload("order_webhook_1","pay_wh_g");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_g_1").content(body)).andExpect(status().isOk());
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(bookingRepository.findById(bid).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
	}
	@Test void paymentFailedCannotOverwriteSuccess() throws Exception {
		User u = newUser("wh-h@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e);
		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);
		paymentService.verify(bid, u.getId(), new com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest("order_webhook_1","pay_wh_h","sig",null));
		String body = failedPayload("order_webhook_1","pay_wh_h2");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_h_1").content(body)).andExpect(status().isOk());
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.SUCCESS);
	}
	@Test void paymentCapturedAfterExpirationIgnored() throws Exception {
		User u = newUser("wh-i@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e); UUID seat = seatId(e,"S001");
		Booking b = bookingRepository.findById(bid).orElseThrow(); b.setExpiresAt(Instant.now().minusSeconds(60)); bookingRepository.saveAndFlush(b);
		assertThat(bookingService.expireIfDue(bid)).isTrue();
		String body = capturedPayload("order_webhook_1","pay_wh_i");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_i_1").content(body)).andExpect(status().isOk());
		assertThat(bookingRepository.findById(bid).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.FAILED);
		assertThat(seatRepository.findById(seat).orElseThrow().getStatus()).isEqualTo(SeatStatus.AVAILABLE);
	}
	@Test void refundAfterLocalRefundNoSecondExternalCall() throws Exception {
		User u = newUser("wh-j@example.test"); Event e = newEvent(); UUID bid = reserveAndInitiate(u,e);
		given(paymentProvider.verifySignature(anyString(), anyString(), anyString())).willReturn(true);
		paymentService.verify(bid, u.getId(), new com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest("order_webhook_1","pay_wh_j","sig",null));
		given(paymentProvider.refundPayment(anyString(), any(BigDecimal.class))).willReturn("rfnd_local_j");
		bookingService.cancelBooking(bid, u.getId());
		assertThat(paymentRepository.findByBookingId(bid).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);
		// reset invocation count to isolate webhook
		org.mockito.Mockito.clearInvocations(paymentProvider);
		String body = refundPayload("rfnd_webhook_j","pay_wh_j","order_webhook_1");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_j_1").content(body)).andExpect(status().isOk());
		verify(paymentProvider, never()).refundPayment(anyString(), any(BigDecimal.class));
	}
	@Test void unknownOrderIgnored() throws Exception {
		String body = capturedPayload("order_unknown_999","pay_unknown");
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_k_1").content(body)).andExpect(status().isOk());
		assertThat(webhookEventRepository.findByRazorpayEventId("evt_k_1").orElseThrow().getStatus().name()).isEqualTo("IGNORED");
	}
	@Test void unsupportedEventIgnored() throws Exception {
		String body = "{\"event\":\"order.paid\",\"payload\":{\"order\":{\"entity\":{\"id\":\"order_1\"}}}}";
		String sig = hmac(body.getBytes(StandardCharsets.UTF_8), "test_webhook_secret_1234567890");
		mockMvc.perform(post("/api/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON).header("X-Razorpay-Signature", sig).header("X-Razorpay-Event-Id","evt_l_1").content(body)).andExpect(status().isOk());
		assertThat(webhookEventRepository.findByRazorpayEventId("evt_l_1").orElseThrow().getStatus().name()).isEqualTo("IGNORED");
	}
}
