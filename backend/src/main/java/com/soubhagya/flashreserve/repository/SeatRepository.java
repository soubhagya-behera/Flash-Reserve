package com.soubhagya.flashreserve.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

	List<Seat> findByEventId(UUID eventId);

	Optional<Seat> findByEventIdAndSeatNumber(UUID eventId, String seatNumber);

	List<Seat> findByEventIdAndStatus(UUID eventId, SeatStatus status);

	Optional<Seat> findByIdAndEventId(UUID id, UUID eventId);

	/**
	 * Dashboard aggregate: one grouped count per seat status (AVAILABLE,
	 * HELD, BOOKED) instead of loading every seat row into memory.
	 */
	@Query("select s.status as status, count(s) as total from Seat s group by s.status")
	List<SeatStatusCount> countGroupedByStatus();

	interface SeatStatusCount {

		SeatStatus getStatus();

		long getTotal();
	}

}
