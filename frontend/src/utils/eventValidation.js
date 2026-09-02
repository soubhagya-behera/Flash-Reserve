/* ============================================================
   FlashReserve — Event form validation + instant conversion
   Mirrors the backend CreateEventRequest / UpdateEventRequest
   constraints so obvious mistakes never leave the browser.
   Every validator returns an error message string, or null if OK.
   ============================================================ */

const MAX_NAME = 200
const MAX_DESCRIPTION = 2000
const MAX_VENUE = 255
const MAX_SEATS = 10000

export function validateEventName(value) {
  const trimmed = value.trim()
  if (!trimmed) return 'Name is required'
  if (trimmed.length > MAX_NAME) {
    return `Name must not exceed ${MAX_NAME} characters`
  }
  return null
}

export function validateEventDescription(value) {
  // Optional field — only a length ceiling applies.
  if (value.trim().length > MAX_DESCRIPTION) {
    return `Description must not exceed ${MAX_DESCRIPTION} characters`
  }
  return null
}

export function validateEventVenue(value) {
  const trimmed = value.trim()
  if (!trimmed) return 'Venue is required'
  if (trimmed.length > MAX_VENUE) {
    return `Venue must not exceed ${MAX_VENUE} characters`
  }
  return null
}

export function validateTotalSeats(value) {
  const parsed = Number(value)
  if (value.trim() === '' || !Number.isInteger(parsed)) {
    return 'Total seats is required and must be a whole number'
  }
  if (parsed <= 0) return 'Total seats must be positive'
  if (parsed > MAX_SEATS) return `Total seats must not exceed ${MAX_SEATS}`
  return null
}

export function validateTicketPrice(value) {
  const parsed = Number(value)
  if (value.trim() === '' || !Number.isFinite(parsed)) {
    return 'Ticket price is required'
  }
  if (parsed < 0.01) return 'Ticket price must be at least 0.01'
  return null
}

/**
 * Validates a datetime-local input. Create mode requires a future
 * date; edit mode allows keeping an existing (possibly past) date
 * unchanged — exactly like the backend.
 */
export function validateEventDate(localValue, { initialInstant = null } = {}) {
  if (!localValue) return 'Event date is required'
  const instant = dateTimeLocalToInstant(localValue)
  if (!instant) return 'Enter a valid date and time'
  if (initialInstant && instant === initialInstant) return null
  if (new Date(instant).getTime() <= Date.now()) {
    return 'Event date must be in the future'
  }
  return null
}

/* A datetime-local string has no offset; `new Date(value)` parses it
   as LOCAL time and toISOString() yields the correct UTC instant —
   the browser owns the timezone math, never this code. */
export function dateTimeLocalToInstant(localValue) {
  const date = new Date(localValue)
  return Number.isNaN(date.getTime()) ? null : date.toISOString()
}

/** Reverse mapping for pre-filling the edit form, in local time. */
export function instantToDateTimeLocal(instant) {
  const date = new Date(instant)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (part) => String(part).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}