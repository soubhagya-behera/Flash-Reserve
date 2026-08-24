package com.soubhagya.flashreserve.service;

import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Payment;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.repository.PaymentRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaymentService {

	private final PaymentRepository paymentRepository;

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

}
