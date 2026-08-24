package com.soubhagya.flashreserve.entity;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.SeatStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "seats",
		uniqueConstraints = @UniqueConstraint(name = "uk_seats_event_id_seat_number", columnNames = { "event_id", "seat_number" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", updatable = false, nullable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "event_id", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Event event;

	@NotBlank
	@Column(name = "seat_number", nullable = false, length = 20, updatable = false)
	@Setter(AccessLevel.NONE)
	private String seatNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private SeatStatus status = SeatStatus.AVAILABLE;

	@Version
	@Column(name = "version", nullable = false)
	@Setter(AccessLevel.NONE)
	private long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	@Setter(AccessLevel.NONE)
	private Instant updatedAt;

	public Seat(Event event, String seatNumber) {
		this.event = event;
		this.seatNumber = seatNumber;
	}

	public void setStatus(SeatStatus status) {
		this.status = status;
	}

}
