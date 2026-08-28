package com.soubhagya.flashreserve.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.repository.BookingRepository;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class HoldExpirationService {

	private final BookingRepository bookingRepository;

	private final BookingService bookingService;

	@Scheduled(fixedDelayString = "${reservation.expiration-interval}")
	public void expireDueHolds() {
		List<UUID> dueIds = bookingRepository.findDueHoldIds(BookingStatus.PENDING, Instant.now());
		for (UUID id : dueIds) {
			try {
				bookingService.expireIfDue(id);
			}
			catch (ObjectOptimisticLockingFailureException ex) {
				log.warn("Booking {} changed concurrently while expiring; will re-check on the next pass", id);
			}
			catch (RuntimeException ex) {
				log.error("Failed to expire booking {}", id, ex);
			}
		}
	}

}
