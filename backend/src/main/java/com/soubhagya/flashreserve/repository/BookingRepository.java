package com.soubhagya.flashreserve.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
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
	 * Paginated bookings of a single user. Ownership is constrained in the
	 * query, and event/seat are fetched in the same statement so the response
	 * DTO never triggers per-book lazy loads. Uses ix_bookings_user_id_status.
	 */
	@EntityGraph(attributePaths = { "event", "seat" })
	Page<Booking> findByUserId(UUID userId, Pageable pageable);

	/**
	 * Booking a user is allowed to see - ownership enforced in SQL so a
	 * foreign booking simply does not resolve (safe 404, no existence leak).
	 */
	@EntityGraph(attributePaths = { "event", "seat" })
	Optional<Booking> findByIdAndUserId(UUID id, UUID userId);

	/**
	 * ADMIN catalog queries: event, seat and booker are fetched in the same
	 * statement so a page of admin bookings never triggers per-row lazy
	 * loads. Filter combinations map to dedicated derived queries, each
	 * backed by an existing bookings index.
	 */
	@Override
	@EntityGraph(attributePaths = { "event", "seat", "user" })
	Page<Booking> findAll(Pageable pageable);

	@EntityGraph(attributePaths = { "event", "seat", "user" })
	Page<Booking> findByStatus(BookingStatus status, Pageable pageable);

	@EntityGraph(attributePaths = { "event", "seat", "user" })
	Page<Booking> findByEventId(UUID eventId, Pageable pageable);

	@EntityGraph(attributePaths = { "event", "seat", "user" })
	Page<Booking> findByEventIdAndStatus(UUID eventId, BookingStatus status, Pageable pageable);

	/**
	 * ADMIN detail lookup: one booking with its event, seat and booker
	 * resolved eagerly, regardless of status or owner.
	 */
	@Override
	@EntityGraph(attributePaths = { "event", "seat", "user" })
	Optional<Booking> findById(UUID id);

	/**
	 * ID-only projection for the expiration scan: the job needs identifiers
	 * only, so loading full Booking entities (each pulling lazy associations)
	 * would be wasted work. Uses the existing ix_bookings_expires_at index.
	 */
	@Query("select b.id from Booking b where b.status = :status and b.expiresAt < :threshold")
	List<UUID> findDueHoldIds(@Param("status") BookingStatus status,
			@Param("threshold") Instant threshold);

}
