/* ============================================================
   FlashReserve — Admin dashboard service
   Thin wrapper over the shared apiClient for the ADMIN metrics
   snapshot. Keep API calls out of visual components.
   ============================================================ */

import { apiRequest } from './apiClient.js'

/**
 * Fetches the ADMIN dashboard snapshot: event/booking/seat status
 * counts, confirmed revenue (SUCCESS payments on CONFIRMED bookings
 * only) and the five newest bookings without any booker identity or
 * payment details. The backend answer is authoritative — the frontend
 * never derives these numbers itself. 401/403 mirror the other admin
 * endpoints.
 */
export function getAdminDashboard({ signal } = {}) {
  return apiRequest('/api/admin/dashboard', { signal })
}