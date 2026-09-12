package com.soubhagya.flashreserve;

import java.time.Instant;

import com.soubhagya.flashreserve.entity.WebhookEvent;
import com.soubhagya.flashreserve.entity.enums.WebhookEventStatus;
import com.soubhagya.flashreserve.repository.WebhookEventRepository;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000"
})
class WebhookEventPersistenceTests {

	@Autowired
	private WebhookEventRepository webhookEventRepository;

	@Test
	void migrationAndPersistenceSucceeds() {
		WebhookEvent event = new WebhookEvent(
				"evt_" + System.nanoTime(),
				"payment.captured",
				"order_test_123",
				"pay_test_123",
				null,
				WebhookEventStatus.PROCESSED,
				null,
				Instant.now());
		WebhookEvent saved = webhookEventRepository.saveAndFlush(event);
		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
		assertThat(webhookEventRepository.findByRazorpayEventId(saved.getRazorpayEventId())).isPresent();
		assertThat(webhookEventRepository.existsByRazorpayEventId(saved.getRazorpayEventId())).isTrue();
	}

	@Test
	void duplicateRazorpayEventIdIsRejectedByDatabase() {
		String eventId = "evt_dup_" + System.nanoTime();
		WebhookEvent first = new WebhookEvent(
				eventId,
				"payment.captured",
				"order_dup_1",
				"pay_dup_1",
				null,
				WebhookEventStatus.PROCESSED,
				null,
				Instant.now());
		webhookEventRepository.saveAndFlush(first);

		WebhookEvent duplicate = new WebhookEvent(
				eventId,
				"payment.captured",
				"order_dup_1",
				"pay_dup_1",
				null,
				WebhookEventStatus.PROCESSED,
				null,
				Instant.now());

		assertThatThrownBy(() -> webhookEventRepository.saveAndFlush(duplicate))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void ignoredStatusPersistsWithReason() {
		WebhookEvent ignored = new WebhookEvent(
				"evt_ign_" + System.nanoTime(),
				"payment.failed",
				"order_ign_1",
				"pay_ign_1",
				null,
				WebhookEventStatus.IGNORED,
				"UNKNOWN_ORDER",
				Instant.now());
		WebhookEvent saved = webhookEventRepository.saveAndFlush(ignored);
		assertThat(saved.getStatus()).isEqualTo(WebhookEventStatus.IGNORED);
		assertThat(saved.getReason()).isEqualTo("UNKNOWN_ORDER");
	}
}
