package com.soubhagya.flashreserve.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

	List<Seat> findByEventId(UUID eventId);

	Optional<Seat> findByEventIdAndSeatNumber(UUID eventId, String seatNumber);

	List<Seat> findByEventIdAndStatus(UUID eventId, SeatStatus status);

	Optional<Seat> findByIdAndEventId(UUID id, UUID eventId);

}
