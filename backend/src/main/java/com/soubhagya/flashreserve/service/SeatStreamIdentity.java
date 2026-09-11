package com.soubhagya.flashreserve.service;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * Stable identity of this backend JVM for the seat-update stream. The
 * publisher tags every Redis envelope with it so the publishing instance can
 * drop its own echo instead of broadcasting the same event twice.
 */
@Component
public class SeatStreamIdentity {

	private final String instanceId = UUID.randomUUID().toString();

	public String instanceId() {
		return instanceId;
	}

}
