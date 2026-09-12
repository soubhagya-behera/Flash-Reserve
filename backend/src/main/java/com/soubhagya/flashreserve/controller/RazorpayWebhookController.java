package com.soubhagya.flashreserve.controller;

import com.soubhagya.flashreserve.service.RazorpayWebhookService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/webhooks/razorpay")
@RequiredArgsConstructor
public class RazorpayWebhookController {

	private final RazorpayWebhookService webhookService;

	@PostMapping
	public ResponseEntity<Void> handle(
			@RequestBody(required = false) byte[] rawBody,
			@RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
			@RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
		if (rawBody == null) rawBody = new byte[0];
		try {
			webhookService.handle(rawBody, signature, eventId);
			return ResponseEntity.ok().build();
		}
		catch (RazorpayWebhookService.WebhookSignatureException ex) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		catch (RazorpayWebhookService.WebhookBadRequestException ex) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
		catch (Exception ex) {
			log.error("Webhook processing failed for event {}", eventId, ex);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}
}
