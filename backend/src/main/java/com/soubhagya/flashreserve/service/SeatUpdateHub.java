package com.soubhagya.flashreserve.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.soubhagya.flashreserve.dto.event.SeatStatusEvent;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory fan-out for seat updates. One registry per instance: emitters are
 * grouped by event id so Event X never reaches Event Y subscribers. No Redis
 * subscription per browser and no database query on this path. A single shared
 * scheduler sends SSE comment heartbeats to every open emitter.
 */
@Slf4j
@Component
public class SeatUpdateHub {

	private final ConcurrentHashMap<UUID, CopyOnWriteArraySet<SseEmitter>> emitters = new ConcurrentHashMap<>();

	private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();

	public SeatUpdateHub() {
		heartbeats.scheduleAtFixedRate(this::heartbeat, 25, 25, TimeUnit.SECONDS);
	}

	public void add(UUID eventId, SseEmitter emitter) {
		emitters.computeIfAbsent(eventId, key -> new CopyOnWriteArraySet<>()).add(emitter);
		emitter.onCompletion(() -> remove(eventId, emitter));
		emitter.onTimeout(() -> remove(eventId, emitter));
		emitter.onError(error -> remove(eventId, emitter));
	}

	public void remove(UUID eventId, SseEmitter emitter) {
		CopyOnWriteArraySet<SseEmitter> group = emitters.get(eventId);
		if (group != null) {
			group.remove(emitter);
		}
	}

	public void broadcast(SeatStatusEvent event) {
		CopyOnWriteArraySet<SseEmitter> group = emitters.get(event.eventId());
		if (group == null || group.isEmpty()) {
			return;
		}
		for (SseEmitter emitter : group) {
			try {
				emitter.send(SseEmitter.event().id(event.seatId() + ":" + event.seatVersion())
						.name("seat-status").data(event));
			}
			catch (Exception ex) {
				log.debug("Dropping dead seat-update emitter: {}", ex.getMessage());
				remove(event.eventId(), emitter);
				emitter.completeWithError(ex);
			}
		}
	}

	public int count(UUID eventId) {
		CopyOnWriteArraySet<SseEmitter> group = emitters.get(eventId);
		return group == null ? 0 : group.size();
	}

	public int total() {
		int sum = 0;
		for (CopyOnWriteArraySet<SseEmitter> group : emitters.values()) {
			sum += group.size();
		}
		return sum;
	}

	private void heartbeat() {
		List<Map.Entry<UUID, SseEmitter>> snapshot = new ArrayList<>();
		for (Map.Entry<UUID, CopyOnWriteArraySet<SseEmitter>> entry : emitters.entrySet()) {
			for (SseEmitter emitter : entry.getValue()) {
				snapshot.add(Map.entry(entry.getKey(), emitter));
			}
		}
		for (Map.Entry<UUID, SseEmitter> target : snapshot) {
			try {
				target.getValue().send(SseEmitter.event().comment("hb"));
			}
			catch (Exception ex) {
				remove(target.getKey(), target.getValue());
			}
		}
	}

	@PreDestroy
	void shutdown() {
		heartbeats.shutdownNow();
	}

}
