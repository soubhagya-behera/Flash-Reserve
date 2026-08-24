package com.soubhagya.flashreserve.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

	List<Booking> findByUserId(UUID userId);

	List<Booking> findByEventId(UUID eventId);

	List<Booking> findBySeatId(UUID seatId);

	List<Booking> findByStatus(BookingStatus status);

	List<Booking> findByStatusAndExpiresAtBefore(BookingStatus status, Instant threshold);

}
