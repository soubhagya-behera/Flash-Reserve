package com.soubhagya.flashreserve.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.enums.EventStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface EventRepository extends JpaRepository<Event, UUID> {

	Page<Event> findByStatus(EventStatus status, Pageable pageable);

	/**
	 * Dashboard aggregate: one grouped count per event status instead of
	 * loading events into memory. Absent statuses simply mean zero.
	 */
	@Query("select e.status as status, count(e) as total from Event e group by e.status")
	List<EventStatusCount> countGroupedByStatus();

	/**
	 * Dashboard "upcoming" count: PUBLISHED events with a future date.
	 * Backed by ix_events_status_event_date (status, event_date).
	 */
	long countByStatusAndEventDateAfter(EventStatus status, Instant date);

	interface EventStatusCount {

		EventStatus getStatus();

		long getTotal();
	}

}
