package com.soubhagya.flashreserve.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

	List<Booking> findByUserId(UUID userId);

	List<Booking> findByEventId(UUID eventId);

	List<Booking> findBySeatId(UUID seatId);

	List<Booking> findByStatus(BookingStatus status);

	/**
	 * ID-only projection for the expiration scan: the job needs identifiers
	 * only, so loading full Booking entities (each pulling lazy associations)
	 * would be wasted work. Uses the existing ix_bookings_expires_at index.
	 */
	@Query("select b.id from Booking b where b.status = :status and b.expiresAt < :threshold")
	List<UUID> findDueHoldIds(@Param("status") BookingStatus status,
			@Param("threshold") Instant threshold);

}
