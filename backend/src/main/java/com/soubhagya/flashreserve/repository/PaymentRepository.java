package com.soubhagya.flashreserve.repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Payment;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.PaymentStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

	Optional<Payment> findByBookingId(UUID bookingId);

	Optional<Payment> findByPaymentReference(String paymentReference);

	Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

	/**
	 * Dashboard revenue aggregate: exactly one payment per booking
	 * (uk_payments_booking_id), so a single grouped sum is unambiguous.
	 * Revenue requires BOTH payment SUCCESS and booking CONFIRMED — the
	 * join keeps payment/booking state consistent even if a row ever
	 * violated the single-transaction invariant. PENDING, FAILED and
	 * REFUNDED payments never contribute.
	 */
	@Query("""
			select count(p) as paymentCount, coalesce(sum(p.amount), 0) as totalAmount
			from Payment p join p.booking b
			where p.status = :paymentStatus and b.status = :bookingStatus""")
	RevenueTotals sumRevenue(@Param("paymentStatus") PaymentStatus paymentStatus,
			@Param("bookingStatus") BookingStatus bookingStatus);

	interface RevenueTotals {

		long getPaymentCount();

		BigDecimal getTotalAmount();
	}

}
