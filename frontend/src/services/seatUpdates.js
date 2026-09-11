/* ============================================================
   FlashReserve - Seat-update stream helpers
   Pure reducer + validator for SSE seat-status events. No DOM,
   no network, no React. Used by useSeatUpdates and node --test.
   ============================================================ */

export function seatUpdatesUrl(eventId) {
  return `/api/events/${encodeURIComponent(eventId)}/seat-updates`
}

export function isSeatStatusEvent(value) {
  if (!value || typeof value !== 'object') return false
  if (typeof value.seatId !== 'string' || !value.seatId) return false
  if (typeof value.seatNumber !== 'string' || !value.seatNumber) return false
  if (!['AVAILABLE', 'HELD', 'BOOKED'].includes(value.status)) return false
  if (!Number.isInteger(value.seatVersion) || value.seatVersion < 0) return false
  return true
}

export function versionsFromSnapshot(seats) {
  const m = new Map()
  for (const s of seats ?? []) {
    if (s && typeof s.id === 'string' && Number.isInteger(s.seatVersion)) {
      m.set(s.id, s.seatVersion)
    }
  }
  return m
}

/**
 * Applies one seat-status event to a seat array. Version-guarded:
 * stale/out-of-order events for the same seat are ignored.
 * `versions` maps seatId -> max applied seatVersion.
 */
export function applySeatUpdate(seats, event, versions = new Map()) {
  if (!isSeatStatusEvent(event)) return { seats, versions, applied: false }
  const current = versions.get(event.seatId)
  if (current !== undefined && event.seatVersion <= current) {
    return { seats, versions, applied: false }
  }
  const nextVersions = new Map(versions)
  nextVersions.set(event.seatId, event.seatVersion)
  let found = false
  const nextSeats = seats.map((seat) => {
    if (seat.id !== event.seatId) return seat
    found = true
    if (seat.status === event.status) return seat
    return { ...seat, status: event.status }
  })
  if (!found) return { seats, versions: nextVersions, applied: false, unknown: true }
  return { seats: nextSeats, versions: nextVersions, applied: true }
}
