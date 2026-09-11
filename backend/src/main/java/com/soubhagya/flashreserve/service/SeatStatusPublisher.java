package com.soubhagya.flashreserve.service;

import tools.jackson.databind.ObjectMapper;
import com.soubhagya.flashreserve.dto.event.SeatStatusEvent;

import org.redisson.api.RTopic;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * AFTER_COMMIT-only publisher for seat updates. Callers invoke
 * {@link #publishAfterCommit} strictly after the state-changing database
 * transaction has committed; rollbacks never reach this class. Local SSE
 * fan-out happens first, then a best-effort Redis publish for other
 * instances. Redis failure is swallowed: correctness stays in PostgreSQL,
 * only the notification is lost and the REST snapshot recovers it.
 */
@Slf4j
@Service
public class SeatStatusPublisher {

	public static final String TOPIC_NAME = "flashreserve:seat-updates";

	private final SeatUpdateHub hub;

	private final SeatStreamIdentity identity;

	private final RTopic topic;

	private final ObjectMapper objectMapper;

	public SeatStatusPublisher(SeatUpdateHub hub, SeatStreamIdentity identity,
			org.redisson.api.RedissonClient redisson, ObjectMapper objectMapper) {
		this.hub = hub;
		this.identity = identity;
		this.topic = redisson.getTopic(TOPIC_NAME);
		this.objectMapper = objectMapper;
	}

	public void publishAfterCommit(SeatStatusEvent event) {
		hub.broadcast(event);
		try {
			SeatStreamEnvelope envelope = new SeatStreamEnvelope(identity.instanceId(), event);
			topic.publishAsync(objectMapper.writeValueAsString(envelope));
		}
		catch (Exception ex) {
			log.warn("Seat update notification lost for seat {}: {}", event.seatId(), ex.getMessage());
		}
	}

	public void handleInbound(String message) {
		SeatStreamEnvelope envelope;
		try {
			envelope = objectMapper.readValue(message, SeatStreamEnvelope.class);
		}
		catch (Exception ex) {
			log.warn("Ignoring malformed seat update: {}", ex.getMessage());
			return;
		}
		if (envelope == null || envelope.event() == null) {
			return;
		}
		if (identity.instanceId().equals(envelope.originInstanceId())) {
			return;
		}
		hub.broadcast(envelope.event());
	}

	public record SeatStreamEnvelope(String originInstanceId, SeatStatusEvent event) {
	}

}
