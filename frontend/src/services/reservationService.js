/* ============================================================
   FlashReserve — Reservation API service
   Thin wrapper over the shared apiClient for the reservation
   hot path. Keep API calls out of visual components.
   ============================================================ */

import { apiRequest } from './apiClient.js'

/**
 * Holds one seat for the authenticated user: POST
 * /api/events/{eventId}/seats/{seatId}/reservations.
 * Resolves with ReservationResponse: { bookingId, eventId,
 * seatId, seatNumber, status: 'PENDING', expiresAt, createdAt }.
 * Throws ApiError on 401/403/404/409/429/503.
 */
export function reserveSeat(eventId, seatId, { signal } = {}) {
  return apiRequest(
    `/api/events/${encodeURIComponent(eventId)}/seats/${encodeURIComponent(seatId)}/reservations`,
    { method: 'POST', signal },
  )
}
