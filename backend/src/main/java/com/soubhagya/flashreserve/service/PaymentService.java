package com.soubhagya.flashreserve.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.dto.payment.PaymentConfirmationResponse;
import com.soubhagya.flashreserve.dto.payment.PaymentInitiationResponse;
import com.soubhagya.flashreserve.dto.payment.PaymentVerificationRequest;
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
			// A concurrent initiate for the same booking created the payment first.
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
	 * Verifies a Razorpay checkout result. The signature and order-id checks
	 * are pure computation and run outside any transaction; only the final
	 * state change is a short transaction guarded by Seat {@code @Version}
	 * optimistic locking, so a concurrent cancellation or expiration can never
	 * be overwritten and a CONFIRMED booking always pairs with a BOOKED seat
	 * and a SUCCESS payment.
	 */
	public PaymentConfirmationResponse verify(UUID bookingId, UUID userId,
			PaymentVerificationRequest request) {
		Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		Payment payment = paymentRepository.findByBookingId(bookingId)
				.orElseThrow(() -> new InvalidStateTransitionException(
						"Payment has not been initiated for this booking."));

		if (payment.getStatus() == PaymentStatus.SUCCESS) {
			// Idempotent replay of an already-confirmed payment: the stored,
			// signature-proven outcome is authoritative, so HMAC verification
			// is never re-run and no payment/booking/seat state is touched.
			// The response is assembled inside a transaction because
			// confirmationOf resolves the LAZY seat association and must not
			// run on detached entities (spring.jpa.open-in-view=false).
			return transactionTemplate.execute(status -> confirmationOf(bookingId));
		}

		if (request.isFailed()) {
			// The checkout failed client-side; release the hold consistently.
			return transactionTemplate.execute(status -> markFailed(bookingId, payment.getId()));
		}

		if (!request.razorpayOrderId().equals(payment.getRazorpayOrderId())) {
			throw new PaymentVerificationException(
					"Razorpay order does not match this booking's payment.");
		}
		if (!paymentProvider.verifySignature(request.razorpayOrderId(), request.razorpayPaymentId(),
				request.razorpaySignature())) {
			throw new PaymentVerificationException("Invalid payment signature.");
		}

		return transactionTemplate.execute(status -> confirm(bookingId, payment.getId(),
				request.razorpayPaymentId()));
	}

	private PaymentConfirmationResponse confirm(UUID bookingId, UUID paymentId, String razorpayPaymentId) {
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		if (booking.getStatus() == BookingStatus.CONFIRMED) {
			return confirmationOf(bookingId);
		}
		if (booking.getStatus() != BookingStatus.PENDING) {
			throw new InvalidStateTransitionException(
					"Cannot verify payment for booking in status " + booking.getStatus());
		}

		Seat seat = booking.getSeat();
		if (seat.getStatus() != SeatStatus.HELD) {
			throw new InvalidStateTransitionException("Seat is not held and cannot be booked");
		}
		seat.setStatus(SeatStatus.BOOKED);
		try {
			seatRepository.saveAndFlush(seat);
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			// A concurrent cancellation/expiration changed the seat first.
			throw new InvalidStateTransitionException("Booking changed concurrently; payment not confirmed");
		}

		booking.setStatus(BookingStatus.CONFIRMED);
		Payment payment = paymentRepository.findById(paymentId).orElseThrow();
		payment.setStatus(PaymentStatus.SUCCESS);
		payment.setRazorpayPaymentId(razorpayPaymentId);
		return confirmationOf(bookingId);
	}

	private PaymentConfirmationResponse markFailed(UUID bookingId, UUID paymentId) {
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		if (booking.getStatus() == BookingStatus.PENDING) {
			Seat seat = booking.getSeat();
			if (seat.getStatus() == SeatStatus.HELD) {
				seat.setStatus(SeatStatus.AVAILABLE);
				try {
					seatRepository.saveAndFlush(seat);
				}
				catch (ObjectOptimisticLockingFailureException ex) {
					throw new InvalidStateTransitionException(
							"Booking changed concurrently; payment not failed");
				}
			}
			booking.setStatus(BookingStatus.CANCELLED);
		}
		Payment payment = paymentRepository.findById(paymentId).orElseThrow();
		payment.setStatus(PaymentStatus.FAILED);
		return confirmationOf(bookingId);
	}

	/**
	 * Cancels a CONFIRMED paid booking with a full refund. The Razorpay
	 * refund is requested FIRST, outside any database transaction, and only
	 * after the provider accepts it do the local state changes (Payment
	 * REFUNDED + refund reference, Booking CANCELLED, Seat BOOKED ->
	 * AVAILABLE) run in one short atomic transaction guarded by the Seat
	 * {@code @Version} optimistic lock. If the provider rejects the refund or
	 * is unreachable, nothing local changes: the booking stays CONFIRMED and
	 * the seat stays BOOKED.
	 *
	 * The per-seat Redis lock serializes concurrent cancellations of the same
	 * booking (and against the reservation hot path), so a duplicated or
	 * retried request can never issue a second refund: a replay always finds
	 * the booking already CANCELLED with the refund reference persisted, and
	 * is rejected without ever touching the provider again.
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
			// Includes the replay of an already-cancelled booking: the
			// persisted razorpay refund reference proves the money was
			// reversed, so the safe replay behavior is to reject without
			// re-refunding, re-releasing the seat or re-transitioning.
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

		// Slow external provider call - deliberately OUTSIDE any database
		// transaction. Nothing local has been mutated yet, so a failure here
		// leaves booking CONFIRMED, payment SUCCESS and seat BOOKED.
		String refundId = paymentProvider.refundPayment(payment.getRazorpayPaymentId(),
				payment.getAmount());

		return transactionTemplate.execute(
				status -> applyConfirmedCancellation(bookingId, payment.getId(), refundId));
	}

	/**
	 * The single atomic database state change after a successful provider
	 * refund. The per-seat Redis lock makes a concurrent writer to this
	 * booking/seat pair practically impossible; the defensive re-checks and
	 * the Seat {@code @Version} optimistic lock are the final safety net.
	 */
	private Booking applyConfirmedCancellation(UUID bookingId, UUID paymentId, String refundId) {
		Booking booking = bookingRepository.findById(bookingId)
				.orElseThrow(() -> new ResourceNotFoundException("Booking not found: " + bookingId));
		if (booking.getStatus() == BookingStatus.CANCELLED) {
			// A concurrent cancellation already committed - never release
			// the seat a second time and never re-write the payment.
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
