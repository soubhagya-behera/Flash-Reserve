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

/* ============================================================
   Admin endpoints (ROLE_ADMIN required by the backend).
   Same PagedModel envelope as the public list, but DRAFT,
   PUBLISHED, CANCELLED and COMPLETED events are all visible.
   ============================================================ */

/**
 * Lists every event for administration, optionally narrowed to one
 * status (DRAFT | PUBLISHED | CANCELLED | COMPLETED). Backend default
 * sort is createdAt descending — newest created first.
 */
export function listAdminEvents({ status, page = 0, size = 10, signal } = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) })
  if (status) query.set('status', status)
  return apiRequest(`/api/admin/events?${query.toString()}`, { signal })
}

/**
 * Fetches one event by UUID regardless of status. 404 for unknown
 * ids, 400 for a malformed UUID.
 */
export function getAdminEvent(eventId, { signal } = {}) {
  return apiRequest(`/api/admin/events/${encodeURIComponent(eventId)}`, { signal })
}

/** Creates a DRAFT event (and its seat inventory) -> 201 EventResponse. */
export function createAdminEvent(body) {
  return apiRequest('/api/admin/events', { method: 'POST', body })
}

/**
 * Replaces the editable fields of an event. totalSeats is fixed at
 * creation and is never sent. Permitted in every status; moving the
 * date into the past is rejected by the backend with 409.
 */
export function updateAdminEvent(eventId, body) {
  return apiRequest(`/api/admin/events/${encodeURIComponent(eventId)}`, {
    method: 'PUT',
    body,
  })
}

/** DRAFT -> PUBLISHED. Any other source status is a 409 conflict. */
export function publishAdminEvent(eventId) {
  return apiRequest(`/api/admin/events/${encodeURIComponent(eventId)}/publish`, {
    method: 'PATCH',
  })
}

/** DRAFT/PUBLISHED -> CANCELLED. Any other source status is a 409. */
export function cancelAdminEvent(eventId) {
  return apiRequest(`/api/admin/events/${encodeURIComponent(eventId)}/cancel`, {
    method: 'PATCH',
  })
}
