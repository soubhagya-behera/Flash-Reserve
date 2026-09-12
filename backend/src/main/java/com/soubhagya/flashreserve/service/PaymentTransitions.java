package com.soubhagya.flashreserve.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.event.SeatStatusEvent;
import com.soubhagya.flashreserve.dto.payment.PaymentConfirmationResponse;
import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.Payment;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.PaymentStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.PaymentRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Single transactional owner for payment-related state machines.
 * Serializes concurrent confirmation / failure / expiration via
 * {@code Payment} PESSIMISTIC_WRITE; {@code Seat.version} remains
 * defense-in-depth for direct seat races.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransitions {

	private final PaymentRepository paymentRepository;

	private final BookingRepository bookingRepository;

	private final SeatRepository seatRepository;

	private final SeatStatusPublisher seatStatusPublisher;

	/**
	 * Confirms a held booking after external HMAC verification.
	 * HMAC must be verified OUTSIDE this transaction by the caller.
	 */
	@Transactional
	public PaymentConfirmationResponse confirmByBookingId(UUID bookingId, String razorpayPaymentId) {
		Payment payment = paymentRepository.findByBookingIdForUpdate(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found for booking: " + bookingId));
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		Seat seat = booking.getSeat();

		if (payment.getStatus() == PaymentStatus.SUCCESS
				&& booking.getStatus() == BookingStatus.CONFIRMED
				&& seat.getStatus() == SeatStatus.BOOKED) {
			return confirmationOf(bookingId);
		}
		if (payment.getStatus() != PaymentStatus.PENDING) {
			log.warn("Ignoring confirm for payment {} in terminal status {}", payment.getId(), payment.getStatus());
			return confirmationOf(bookingId);
		}
		if (booking.getStatus() != BookingStatus.PENDING || seat.getStatus() != SeatStatus.HELD) {
			log.warn("Ignoring confirm for booking {} seat {} in status {}/{}", bookingId,
					seat.getId(), booking.getStatus(), seat.getStatus());
			return confirmationOf(bookingId);
		}

		seat.setStatus(SeatStatus.BOOKED);
		try {
			seatRepository.saveAndFlush(seat);
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			log.warn("Seat {} changed concurrently during confirm for booking {}", seat.getId(), bookingId);
			throw new org.springframework.dao.OptimisticLockingFailureException("Seat changed concurrently", ex);
		}
		booking.setStatus(BookingStatus.CONFIRMED);
		payment.setStatus(PaymentStatus.SUCCESS);
		payment.setRazorpayPaymentId(razorpayPaymentId);

		SeatStatusEvent event = SeatStatusEventFactory.booked(booking);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				seatStatusPublisher.publishAfterCommit(event);
			}
		});
		return confirmationOf(bookingId);
	}

	/**
	 * Future webhook path: confirm by Razorpay order id.
	 */
	@Transactional
	public PaymentConfirmationResponse confirmByOrderId(String razorpayOrderId, String razorpayPaymentId) {
		Payment payment = paymentRepository.findByRazorpayOrderIdForUpdate(razorpayOrderId)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found for order: " + razorpayOrderId));
		return confirmByBookingId(payment.getBooking().getId(), razorpayPaymentId);
	}

	/**
	 * Payment failure reconciliation: PENDING -> FAILED with
	 * PENDING -> CANCELLED and HELD -> AVAILABLE atomically.
	 * Terminal SUCCESS/REFUNDED payments are never overwritten.
	 */
	@Transactional
	public PaymentConfirmationResponse failByBookingId(UUID bookingId) {
		Payment payment = paymentRepository.findByBookingIdForUpdate(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found for booking: " + bookingId));
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		Seat seat = booking.getSeat();

		if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.REFUNDED) {
			log.warn("Ignoring fail for payment {} already {}", payment.getId(), payment.getStatus());
			return confirmationOf(bookingId);
		}
		if (payment.getStatus() == PaymentStatus.FAILED) {
			return confirmationOf(bookingId);
		}
		if (payment.getStatus() != PaymentStatus.PENDING) {
			return confirmationOf(bookingId);
		}

		boolean released = false;
		if (booking.getStatus() == BookingStatus.PENDING) {
			if (seat.getStatus() == SeatStatus.HELD) {
				seat.setStatus(SeatStatus.AVAILABLE);
				try {
					seatRepository.saveAndFlush(seat);
				}
				catch (ObjectOptimisticLockingFailureException ex) {
					throw new org.springframework.dao.OptimisticLockingFailureException("Seat changed concurrently", ex);
				}
				released = true;
			}
			booking.setStatus(BookingStatus.CANCELLED);
		}
		payment.setStatus(PaymentStatus.FAILED);

		if (released) {
			Booking refreshed = bookingRepository.findById(bookingId).orElseThrow();
			SeatStatusEvent event = SeatStatusEventFactory.available(refreshed);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					seatStatusPublisher.publishAfterCommit(event);
				}
			});
		}
		return confirmationOf(bookingId);
	}

	@Transactional
	public PaymentConfirmationResponse failByOrderId(String razorpayOrderId) {
		Payment payment = paymentRepository.findByRazorpayOrderIdForUpdate(razorpayOrderId)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found for order: " + razorpayOrderId));
		return failByBookingId(payment.getBooking().getId());
	}

	/**
	 * Expires a due hold atomically: Booking PENDING->EXPIRED,
	 * Seat HELD->AVAILABLE, Payment PENDING->FAILED in one TX.
	 * Serialized via Payment PESSIMISTIC_WRITE to race safely
	 * against confirmation.
	 */
	@Transactional
	public boolean expireIfDue(UUID bookingId) {
		Optional<Payment> paymentOpt = paymentRepository.findByBookingIdForUpdate(bookingId);
		Booking booking = bookingRepository.findById(bookingId).orElse(null);
		if (booking == null || !isDue(booking)) {
			return false;
		}
		Seat seat = booking.getSeat();
		if (seat.getStatus() == SeatStatus.BOOKED) {
			log.warn("Booking {} is due but its seat is already BOOKED; hold not released", bookingId);
			return false;
		}
		if (booking.getStatus() != BookingStatus.PENDING) {
			return false;
		}
		SeatStatusEvent released = null;
		if (seat.getStatus() == SeatStatus.HELD) {
			seat.setStatus(SeatStatus.AVAILABLE);
			try {
				seatRepository.saveAndFlush(seat);
			}
			catch (ObjectOptimisticLockingFailureException ex) {
				log.warn("Seat {} changed concurrently while expiring booking {}", seat.getId(), bookingId);
				throw ex;
			}
			released = SeatStatusEventFactory.available(booking);
		}
		booking.setStatus(BookingStatus.EXPIRED);
		if (paymentOpt.isPresent()) {
			Payment payment = paymentOpt.get();
			if (payment.getStatus() == PaymentStatus.PENDING) {
				payment.setStatus(PaymentStatus.FAILED);
			}
		}
		if (released != null) {
			SeatStatusEvent published = released;
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					seatStatusPublisher.publishAfterCommit(published);
				}
			});
		}
		return true;
	}

	/**
	 * Variant for sweeper that only knows bookingId after scanning due ids.
	 * Checks due after acquiring lock to avoid resurrecting a concurrently confirmed hold.
	 */
	private boolean isDue(Booking booking) {
		return booking.getStatus() == BookingStatus.PENDING
				&& booking.getExpiresAt() != null
				&& booking.getExpiresAt().isBefore(Instant.now());
	}

	/**
	 * Provider-side refund reconciliation: never calls external refund API.
	 * If CONFIRMED/BOOKED/SUCCESS, transitions to CANCELLED/AVAILABLE/REFUNDED
	 * atomically and publishes AVAILABLE after commit. Otherwise ignored.
	 */
	@Transactional
	public boolean reconcileRefund(String razorpayPaymentId, String razorpayOrderId, String razorpayRefundId) {
		Payment payment = null;
		if (razorpayPaymentId != null && !razorpayPaymentId.isBlank()) {
			payment = paymentRepository.findByRazorpayPaymentIdForUpdate(razorpayPaymentId).orElse(null);
		}
		if (payment == null && razorpayOrderId != null && !razorpayOrderId.isBlank()) {
			payment = paymentRepository.findByRazorpayOrderIdForUpdate(razorpayOrderId).orElse(null);
		}
		if (payment == null) {
			throw new ResourceNotFoundException("Payment not found for refund: " + razorpayRefundId);
		}
		UUID bookingIdForRefund = payment.getBooking().getId();
		Booking booking = bookingRepository.findById(bookingIdForRefund)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingIdForRefund));
		Seat seat = booking.getSeat();

		if (payment.getStatus() == PaymentStatus.REFUNDED) {
			if (razorpayRefundId != null && razorpayRefundId.equals(payment.getRazorpayRefundId())) {
				return false;
			}
			return false;
		}
		if (payment.getStatus() != PaymentStatus.SUCCESS) {
			return false;
		}
		if (booking.getStatus() != BookingStatus.CONFIRMED || seat.getStatus() != SeatStatus.BOOKED) {
			return false;
		}
		seat.setStatus(SeatStatus.AVAILABLE);
		try {
			seatRepository.saveAndFlush(seat);
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			throw new org.springframework.dao.OptimisticLockingFailureException("Seat changed concurrently", ex);
		}
		booking.setStatus(BookingStatus.CANCELLED);
		payment.setStatus(PaymentStatus.REFUNDED);
		payment.setRazorpayRefundId(razorpayRefundId);
		SeatStatusEvent event = SeatStatusEventFactory.available(booking);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				seatStatusPublisher.publishAfterCommit(event);
			}
		});
		return true;
	}

	private PaymentConfirmationResponse confirmationOf(UUID bookingId) {
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		Payment payment = paymentRepository.findByBookingId(bookingId).orElseThrow();
		Seat seat = booking.getSeat();
		return new PaymentConfirmationResponse(bookingId, payment.getPaymentReference(),
				payment.getRazorpayPaymentId(), payment.getAmount(), payment.getStatus(),
				booking.getStatus(), seat.getStatus());
	}
}
