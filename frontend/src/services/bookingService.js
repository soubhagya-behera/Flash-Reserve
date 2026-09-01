/* ============================================================
   FlashReserve — Booking API service
   Thin wrappers over the shared apiClient for the owner-scoped
   booking endpoints. The backend derives ownership from the JWT —
   the frontend never sends a userId. Keep API calls out of
   visual components.
   ============================================================ */

import { apiRequest } from './apiClient.js'

/**
 * Lists the authenticated user's own bookings: GET /api/bookings.
 * Backend answer is a Spring PagedModel: { content: [...],
 * page: { size, number, totalElements, totalPages } }, newest
 * bookings first.
 */
export function listOwnBookings({ page = 0, size = 10, signal } = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return apiRequest(`/api/bookings?${query.toString()}`, { signal })
}

/**
 * Fetches one of the caller's bookings by UUID: GET
 * /api/bookings/{bookingId}. Unknown and foreign bookings are
 * indistinguishable — both are 404 by design.
 */
export function getBooking(bookingId, { signal } = {}) {
  return apiRequest(`/api/bookings/${encodeURIComponent(bookingId)}`, { signal })
}

/**
 * Cancels one of the caller's PENDING bookings and releases its
 * seat: POST /api/bookings/{bookingId}/cancel. Resolves with the
 * updated BookingResponse (status CANCELLED) from the backend.
 * 409 when the booking is not PENDING or changed concurrently.
 */
export function cancelBooking(bookingId, { signal } = {}) {
  return apiRequest(
    `/api/bookings/${encodeURIComponent(bookingId)}/cancel`,
    { method: 'POST', signal },
  )
}
