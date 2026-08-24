package com.soubhagya.flashreserve.entity;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.EventStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "events", indexes = @Index(name = "ix_events_status_event_date", columnList = "status,event_date"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", updatable = false, nullable = false)
	@Setter(AccessLevel.NONE)
	private UUID id;

	@NotBlank
	@Column(name = "name", nullable = false, length = 200)
	private String name;

	@Column(name = "description", length = 2000)
	private String description;

	@NotBlank
	@Column(name = "venue", nullable = false, length = 255)
	private String venue;

	@NotNull
	@Column(name = "event_date", nullable = false)
	private Instant eventDate;

	@Positive
	@Column(name = "total_seats", nullable = false)
	private int totalSeats;

	@NotNull
	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private EventStatus status = EventStatus.DRAFT;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	@Setter(AccessLevel.NONE)
	private Instant updatedAt;

	public Event(String name, String description, String venue, Instant eventDate, int totalSeats) {
		this.name = name;
		this.description = description;
		this.venue = venue;
		this.eventDate = eventDate;
		this.totalSeats = totalSeats;
	}

}
