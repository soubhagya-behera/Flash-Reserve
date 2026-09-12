package com.soubhagya.flashreserve.service;

import java.time.Instant;

import com.soubhagya.flashreserve.config.RazorpayProperties;
import com.soubhagya.flashreserve.entity.WebhookEvent;
import com.soubhagya.flashreserve.entity.enums.PaymentStatus;
import com.soubhagya.flashreserve.entity.enums.WebhookEventStatus;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.payment.RazorpayWebhookSignatureVerifier;
import com.soubhagya.flashreserve.repository.PaymentRepository;
import com.soubhagya.flashreserve.repository.WebhookEventRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RazorpayWebhookService {

	private final RazorpayProperties razorpayProperties;

	private final RazorpayWebhookSignatureVerifier verifier;

	private final ObjectMapper objectMapper;

	private final WebhookEventRepository webhookEventRepository;

	private final PaymentRepository paymentRepository;

	private final PaymentTransitions paymentTransitions;

	public void handle(byte[] rawBody, String signature, String eventId) {
		String secret = razorpayProperties.webhookSecret();
		if (secret == null || secret.isBlank()) {
			log.warn("Webhook secret not configured; rejecting webhook {}", eventId);
			throw new WebhookSignatureException("Webhook secret not configured");
		}
		if (!verifier.verify(rawBody, signature, secret)) {
			throw new WebhookSignatureException("Invalid webhook signature");
		}
		if (eventId == null || eventId.isBlank()) {
			throw new WebhookBadRequestException("Missing X-Razorpay-Event-Id");
		}
		JsonNode root;
		try {
			root = objectMapper.readTree(rawBody);
		}
		catch (Exception ex) {
			throw new WebhookBadRequestException("Malformed webhook JSON");
		}
		String eventType = root.path("event").asText(null);
		if (eventType == null || eventType.isBlank()) {
			throw new WebhookBadRequestException("Missing event type");
		}
		Parsed parsed = extract(root, eventType);
		processInTransaction(eventId, eventType, parsed.orderId, parsed.paymentId, parsed.refundId);
	}

	private record Parsed(String orderId, String paymentId, String refundId) {}

	private Parsed extract(JsonNode root, String eventType) {
		JsonNode payload = root.path("payload");
		String orderId = null;
		String paymentId = null;
		String refundId = null;
		if (eventType.startsWith("payment.")) {
			JsonNode payment = payload.path("payment").path("entity");
			if (payment.isMissingNode() || payment.isNull()) {
				payment = payload.path("payment");
			}
			orderId = text(payment.path("order_id"));
			paymentId = text(payment.path("id"));
			if (orderId == null) {
				orderId = text(payload.path("order").path("entity").path("id"));
				if (orderId == null) orderId = text(payload.path("order").path("id"));
			}
		}
		else if ("refund.created".equals(eventType)) {
			JsonNode refund = payload.path("refund").path("entity");
			if (refund.isMissingNode() || refund.isNull()) refund = payload.path("refund");
			refundId = text(refund.path("id"));
			paymentId = text(refund.path("payment_id"));
			JsonNode payment = payload.path("payment").path("entity");
			if (payment.isMissingNode() || payment.isNull()) payment = payload.path("payment");
			orderId = text(payment.path("order_id"));
			if (paymentId == null) paymentId = text(payment.path("id"));
		}
		return new Parsed(orderId, paymentId, refundId);
	}

	private static String text(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) return null;
		String v = node.asText(null);
		return (v == null || v.isBlank()) ? null : v;
	}

	@Transactional
	protected void processInTransaction(String eventId, String eventType, String orderId, String paymentId, String refundId) {
		if (webhookEventRepository.existsByRazorpayEventId(eventId)) {
			log.info("Duplicate webhook {} type {} ignored", eventId, eventType);
			return;
		}
		WebhookEventStatus status;
		String reason = null;
		try {
			boolean transitioned = dispatch(eventType, orderId, paymentId, refundId);
			if (isSupported(eventType)) {
				if (transitioned) {
					status = WebhookEventStatus.PROCESSED;
				}
				else {
					status = WebhookEventStatus.IGNORED;
					reason = "no state change or not applicable";
				}
			}
			else {
				status = WebhookEventStatus.IGNORED;
				reason = "unsupported event";
			}
		}
		catch (ResourceNotFoundException ex) {
			status = WebhookEventStatus.IGNORED;
			reason = "unknown local order/payment";
			log.warn("Webhook {} {} unknown order/payment: {}", eventId, eventType, ex.getMessage());
		}
		WebhookEvent event = new WebhookEvent(eventId, eventType, orderId, paymentId, refundId, status, reason, Instant.now());
		try {
			webhookEventRepository.saveAndFlush(event);
		}
		catch (DataIntegrityViolationException ex) {
			log.info("Duplicate webhook event {} on save, ignored", eventId);
		}
		log.info("Webhook {} type {} order {} payment {} refund {} -> {}", eventId, eventType, orderId, paymentId, refundId, status);
	}

	private boolean dispatch(String eventType, String orderId, String paymentId, String refundId) {
		return switch (eventType) {
			case "payment.captured" -> dispatchCaptured(orderId, paymentId);
			case "payment.failed" -> dispatchFailed(orderId, paymentId);
			case "refund.created" -> dispatchRefund(paymentId, orderId, refundId);
			default -> false;
		};
	}

	private boolean dispatchCaptured(String orderId, String paymentId) {
		if (orderId == null || paymentId == null) return false;
		var before = paymentRepository.findByRazorpayOrderId(orderId).orElse(null);
		PaymentStatus beforeStatus = before != null ? before.getStatus() : null;
		try {
			paymentTransitions.confirmByOrderId(orderId, paymentId);
		}
		catch (ResourceNotFoundException ex) {
			throw ex;
		}
		var after = paymentRepository.findByRazorpayOrderId(orderId).orElse(null);
		if (before == null || after == null) return false;
		return beforeStatus != PaymentStatus.SUCCESS && after.getStatus() == PaymentStatus.SUCCESS;
	}

	private boolean dispatchFailed(String orderId, String paymentId) {
		if (orderId == null) return false;
		var before = paymentRepository.findByRazorpayOrderId(orderId).orElse(null);
		PaymentStatus beforeStatus = before != null ? before.getStatus() : null;
		try {
			paymentTransitions.failByOrderId(orderId);
		}
		catch (ResourceNotFoundException ex) {
			throw ex;
		}
		var after = paymentRepository.findByRazorpayOrderId(orderId).orElse(null);
		if (before == null || after == null) return false;
		return beforeStatus == PaymentStatus.PENDING && after.getStatus() == PaymentStatus.FAILED;
	}

	private boolean dispatchRefund(String paymentId, String orderId, String refundId) {
		if ((paymentId == null || paymentId.isBlank()) && (orderId == null || orderId.isBlank())) return false;
		var before = paymentId != null ? paymentRepository.findByRazorpayPaymentId(paymentId).orElse(null) : null;
		if (before == null && orderId != null) before = paymentRepository.findByRazorpayOrderId(orderId).orElse(null);
		PaymentStatus beforeStatus = before != null ? before.getStatus() : null;
		boolean transitioned = paymentTransitions.reconcileRefund(paymentId, orderId, refundId);
		if (!transitioned) return false;
		return beforeStatus == PaymentStatus.SUCCESS;
	}

	private static boolean isSupported(String eventType) {
		return "payment.captured".equals(eventType) || "payment.failed".equals(eventType) || "refund.created".equals(eventType);
	}

	public static class WebhookSignatureException extends RuntimeException {
		public WebhookSignatureException(String msg) { super(msg); }
	}

	public static class WebhookBadRequestException extends RuntimeException {
		public WebhookBadRequestException(String msg) { super(msg); }
	}
}
