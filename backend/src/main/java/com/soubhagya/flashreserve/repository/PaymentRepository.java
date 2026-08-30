package com.soubhagya.flashreserve.repository;

import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

	Optional<Payment> findByBookingId(UUID bookingId);

	Optional<Payment> findByPaymentReference(String paymentReference);

	Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

}
