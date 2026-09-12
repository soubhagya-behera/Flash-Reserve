package com.soubhagya.flashreserve.entity;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.WebhookEventStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "webhook_events", uniqueConstraints = @UniqueConstraint(name = "uk_webhook_events_razorpay_event_id", columnNames = "razorpay_event_id"), indexes = {
		@Index(name = "ix_webhook_events_razorpay_order_id", columnList = "razorpay_order_id"),
		@Index(name = "ix_webhook_events_razorpay_payment_id", columnList = "razorpay_payment_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", updatable = false, nullable = false)
	private UUID id;

	@Column(name = "razorpay_event_id", nullable = false, length = 64)
	private String razorpayEventId;

	@Column(name = "event_type", nullable = false, length = 32)
	private String eventType;

	@Column(name = "razorpay_order_id", length = 64)
	private String razorpayOrderId;

	@Column(name = "razorpay_payment_id", length = 64)
	private String razorpayPaymentId;

	@Column(name = "razorpay_refund_id", length = 64)
	private String razorpayRefundId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private WebhookEventStatus status;

	@Column(name = "reason", length = 256)
	private String reason;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	@Setter(AccessLevel.NONE)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	@Setter(AccessLevel.NONE)
	private Instant updatedAt;

	@Column(name = "processed_at", nullable = false)
	private Instant processedAt;

	public WebhookEvent(String razorpayEventId, String eventType, String razorpayOrderId,
			String razorpayPaymentId, String razorpayRefundId, WebhookEventStatus status,
			String reason, Instant processedAt) {
		this.razorpayEventId = razorpayEventId;
		this.eventType = eventType;
		this.razorpayOrderId = razorpayOrderId;
		this.razorpayPaymentId = razorpayPaymentId;
		this.razorpayRefundId = razorpayRefundId;
		this.status = status;
		this.reason = reason;
		this.processedAt = processedAt;
	}

	public void setStatus(WebhookEventStatus status) {
		this.status = status;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public void setProcessedAt(Instant processedAt) {
		this.processedAt = processedAt;
	}

}
