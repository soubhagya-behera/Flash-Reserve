package com.soubhagya.flashreserve.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.PaymentStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payments",
		uniqueConstraints = @UniqueConstraint(name = "uk_payments_booking_id", columnNames = "booking_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", updatable = false, nullable = false)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "booking_id", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Booking booking;

	@NotNull
	@Positive
	@Column(name = "amount", nullable = false, precision = 12, scale = 2)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentStatus status = PaymentStatus.PENDING;

	@Column(name = "payment_reference", length = 100)
	private String paymentReference;

	/**
	 * The provider (Razorpay) order id created for this payment. Verified on
	 * confirmation so a submitted order id that does not belong to this exact
	 * local payment is rejected. Distinct from the local Payment id.
	 */
	@Column(name = "razorpay_order_id", length = 64)
	private String razorpayOrderId;

	/**
	 * The provider (Razorpay) payment id returned after a successful payment.
	 * Set only on confirmation. Never used for lookups; the local database id
	 * and payment reference remain the source of truth.
	 */
	@Column(name = "razorpay_payment_id", length = 64)
	private String razorpayPaymentId;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	@Setter(AccessLevel.NONE)
	private Instant updatedAt;

	public Payment(Booking booking, BigDecimal amount) {
		this.booking = booking;
		this.amount = amount;
	}

	public void setStatus(PaymentStatus status) {
		this.status = status;
	}

	public void setPaymentReference(String paymentReference) {
		this.paymentReference = paymentReference;
	}

	public void setRazorpayOrderId(String razorpayOrderId) {
		this.razorpayOrderId = razorpayOrderId;
	}

	public void setRazorpayPaymentId(String razorpayPaymentId) {
		this.razorpayPaymentId = razorpayPaymentId;
	}

}
