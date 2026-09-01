/* ============================================================
   FlashReserve — Event API service
   Thin wrappers over the shared apiClient for the public Event
   and Seat endpoints. Keep API calls out of visual components.
   ============================================================ */

import { apiRequest } from './apiClient.js'

/**
 * Lists published events. The backend answers with a Spring
 * PagedModel: { content: [...], page: { size, number,
 * totalElements, totalPages } } sorted by event date ascending.
 */
export function listPublishedEvents({ page = 0, size = 24, signal } = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  return apiRequest(`/api/events?${query.toString()}`, { signal })
}

/**
 * Fetches one published event by UUID. 404 when the event is
 * unknown or not published (they are indistinguishable by design).
 */
export function getPublishedEvent(eventId, { signal } = {}) {
  return apiRequest(`/api/events/${encodeURIComponent(eventId)}`, { signal })
}

/**
 * Fetches the full seat map for a published event: an array of
 * { id, seatNumber, status } where status is AVAILABLE, HELD or
 * BOOKED. HELD seats are on a temporary hold and may free up.
 */
export function listEventSeats(eventId, { signal } = {}) {
  return apiRequest(`/api/events/${encodeURIComponent(eventId)}/seats`, { signal })
}
