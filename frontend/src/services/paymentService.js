/* ============================================================
   FlashReserve — Payment API service
   Thin wrappers over the shared apiClient for the existing
   Razorpay TEST MODE endpoints. Keep API calls out of visual
   components. The browser only ever touches the PUBLIC key id —
   the key secret never leaves the backend.
   ============================================================ */

import { apiRequest } from './apiClient.js'

/**
 * Initiates (or idempotently reuses) the Razorpay order for a
 * PENDING booking: POST /api/bookings/{bookingId}/payment. The
 * amount is derived server-side from the event's ticket price.
 * Resolves PaymentInitiationResponse: { bookingId, paymentReference,
 * razorpayOrderId, razorpayKeyId, amount, currency, status }.
 * 409 when the booking is not PENDING; 503 when the provider is
 * unreachable.
 */
export function initiatePayment(bookingId, { signal } = {}) {
  return apiRequest(
    `/api/bookings/${encodeURIComponent(bookingId)}/payment`,
    { method: 'POST', signal },
  )
}

/**
 * Sends a completed Razorpay Checkout result for server-side
 * verification: POST /api/bookings/{bookingId}/payment/verify.
 * Field names mirror the backend DTO (camelCase); Razorpay's own
 * handler supplies snake_case values and is mapped by the caller.
 * Resolves PaymentConfirmationResponse: { bookingId,
 * paymentReference, razorpayPaymentId, amount, paymentStatus,
 * bookingStatus, seatStatus }. 400 on signature/order mismatch.
 */
export function verifyPayment(bookingId, { razorpayOrderId, razorpayPaymentId, razorpaySignature }, { signal } = {}) {
  return apiRequest(
    `/api/bookings/${encodeURIComponent(bookingId)}/payment/verify`,
    {
      method: 'POST',
      body: { razorpayOrderId, razorpayPaymentId, razorpaySignature },
      signal,
    },
  )
}
