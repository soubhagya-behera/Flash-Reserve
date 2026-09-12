package com.soubhagya.flashreserve.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.payment.PaymentConfirmationResponse;
import com.soubhagya.flashreserve.dto.payment.PaymentInitiationResponse;
import com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest;
import com.soubhagya.flashreserve.dto.event.SeatStatusEvent;
import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.Payment;
import com.soubhagya.flashreserve.entity.Seat;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.PaymentStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.exception.InvalidStateTransitionException;
import com.soubhagya.flashreserve.exception.PaymentVerificationException;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.payment.PaymentProvider;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.PaymentRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

	private final PaymentRepository paymentRepository;

	private final BookingRepository bookingRepository;

	private final SeatRepository seatRepository;

	private final PaymentProvider paymentProvider;

	private final ReservationLockService reservationLockService;

	private final TransactionTemplate transactionTemplate;

	private final SeatStatusPublisher seatStatusPublisher;

	private final PaymentTransitions paymentTransitions;

	public Payment getById(UUID id) {
		return paymentRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + id));
	}

	public Optional<Payment> findByBookingId(UUID bookingId) {
		return paymentRepository.findByBookingId(bookingId);
	}

	public Payment getByPaymentReference(String paymentReference) {
		return paymentRepository.findByPaymentReference(paymentReference)
				.orElseThrow(() -> new ResourceNotFoundException(
						"Payment not found with reference: " + paymentReference));
	}

	/**
	 * Creates (or reuses) the Razorpay order for a PENDING booking. The amount
	 * comes exclusively from the event's server-side ticket price - the client
	 * never supplies one. The Razorpay call happens OUTSIDE any database
	 * transaction; only the two small local writes are transactional.
	 */
	public PaymentInitiationResponse initiate(UUID bookingId, UUID userId) {
		Payment payment = transactionTemplate.execute(status -> findOrCreatePayment(bookingId, userId));

		if (payment.getRazorpayOrderId() == null) {
			String razorpayOrderId = paymentProvider.createOrder(payment.getPaymentReference(),
					payment.getAmount());
			UUID paymentId = payment.getId();
			payment = transactionTemplate.execute(status -> attachOrder(paymentId, razorpayOrderId));
		}

		return new PaymentInitiationResponse(bookingId, payment.getPaymentReference(),
				payment.getRazorpayOrderId(), paymentProvider.getClientKeyId(), payment.getAmount(),
				paymentProvider.getCurrency(), payment.getStatus());
	}

	private Payment findOrCreatePayment(UUID bookingId, UUID userId) {
		Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		if (booking.getStatus() == BookingStatus.CONFIRMED) {
			throw new InvalidStateTransitionException("Booking is already confirmed.");
		}
		if (booking.getStatus() != BookingStatus.PENDING) {
			throw new InvalidStateTransitionException(
					"Cannot initiate payment for booking in status " + booking.getStatus());
		}

		Payment existing = paymentRepository.findByBookingId(bookingId).orElse(null);
		if (existing != null) {
			return existing;
		}

		BigDecimal amount = booking.getEvent().getTicketPrice();
		if (amount == null) {
			throw new InvalidStateTransitionException("Event has no ticket price to charge.");
		}

		Payment payment = new Payment(booking, amount);
		payment.setPaymentReference("PAY-" + UUID.randomUUID());
		try {
			return paymentRepository.saveAndFlush(payment);
		}
		catch (DataIntegrityViolationException ex) {
			return paymentRepository.findByBookingId(bookingId).orElseThrow();
		}
	}

	private Payment attachOrder(UUID paymentId, String razorpayOrderId) {
		return transactionTemplate.execute(status -> {
			Payment payment = paymentRepository.findById(paymentId)
					.orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
			if (payment.getRazorpayOrderId() == null) {
				payment.setRazorpayOrderId(razorpayOrderId);
				payment = paymentRepository.saveAndFlush(payment);
			}
			return payment;
		});
	}

	/**
	 * Verifies a Razorpay checkout result. HMAC verification is pure computation
	 * outside any transaction; the local state transition is a single short
	 * transaction serialized via Payment PESSIMISTIC_WRITE in
	 * {@link PaymentTransitions}, so concurrent expiration cannot be overwritten.
	 */
	public PaymentConfirmationResponse verify(UUID bookingId, UUID userId,
			PaymentVerificationRequest request) {
		Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		Payment payment = paymentRepository.findByBookingId(bookingId)
				.orElseThrow(() -> new InvalidStateTransitionException(
						"Payment has not been initiated for this booking."));

		if (payment.getStatus() == PaymentStatus.SUCCESS) {
			return transactionTemplate.execute(status -> confirmationOf(bookingId));
		}

		if (request.isFailed()) {
			return paymentTransitions.failByBookingId(bookingId);
		}

		if (!request.razorpayOrderId().equals(payment.getRazorpayOrderId())) {
			throw new PaymentVerificationException(
					"Razorpay order does not match this booking's payment.");
		}
		if (!paymentProvider.verifySignature(request.razorpayOrderId(), request.razorpayPaymentId(),
				request.razorpaySignature())) {
			throw new PaymentVerificationException("Invalid payment signature.");
		}

		return paymentTransitions.confirmByBookingId(bookingId, request.razorpayPaymentId());
	}

	/**
	 * Cancels a CONFIRMED paid booking with a full refund. The Razorpay
	 * refund is requested FIRST, outside any database transaction, and only
	 * after the provider accepts it do the local state changes run in one
	 * short atomic transaction guarded by the Seat {@code @Version} optimistic
	 * lock. If the provider rejects the refund or is unreachable, nothing local
	 * changes.
	 */
	public Booking cancelConfirmedBooking(UUID bookingId) {
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		return reservationLockService.withSeatLock(booking.getEvent().getId(), booking.getSeat().getId(),
				() -> cancelConfirmedWithinLock(bookingId));
	}

	private Booking cancelConfirmedWithinLock(UUID bookingId) {
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		if (booking.getStatus() != BookingStatus.CONFIRMED) {
			throw new InvalidStateTransitionException(
					"Cannot cancel booking in status " + booking.getStatus());
		}
		if (!booking.getEvent().getEventDate().isAfter(Instant.now())) {
			throw new InvalidStateTransitionException(
					"Cannot cancel a booking after the event has started.");
		}
		Payment payment = paymentRepository.findByBookingId(bookingId)
				.orElseThrow(() -> new InvalidStateTransitionException(
						"No successful payment to refund for this booking."));
		if (payment.getStatus() != PaymentStatus.SUCCESS || payment.getRazorpayPaymentId() == null) {
			throw new InvalidStateTransitionException(
					"No successful payment to refund for this booking.");
		}

		String refundId = paymentProvider.refundPayment(payment.getRazorpayPaymentId(),
				payment.getAmount());

		SeatStatusEvent[] published = new SeatStatusEvent[1];
		Booking cancelled = transactionTemplate.execute(
				status -> applyConfirmedCancellation(bookingId, payment.getId(), refundId, published));
		if (published[0] != null) {
			seatStatusPublisher.publishAfterCommit(published[0]);
		}
		return cancelled;
	}

	private Booking applyConfirmedCancellation(UUID bookingId, UUID paymentId, String refundId,
			SeatStatusEvent[] published) {
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		if (booking.getStatus() == BookingStatus.CANCELLED) {
			return booking;
		}
		if (booking.getStatus() != BookingStatus.CONFIRMED) {
			throw new InvalidStateTransitionException(
					"Booking changed concurrently; refund not applied");
		}
		Seat seat = booking.getSeat();
		if (seat.getStatus() != SeatStatus.BOOKED) {
			throw new InvalidStateTransitionException("Seat is not booked and cannot be released");
		}
		seat.setStatus(SeatStatus.AVAILABLE);
		try {
			seatRepository.saveAndFlush(seat);
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			throw new InvalidStateTransitionException(
					"Booking changed concurrently; refund not applied");
		}
		booking.setStatus(BookingStatus.CANCELLED);
		Payment payment = paymentRepository.findById(paymentId).orElseThrow();
		payment.setStatus(PaymentStatus.REFUNDED);
		payment.setRazorpayRefundId(refundId);
		published[0] = SeatStatusEventFactory.available(booking);
		return booking;
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
