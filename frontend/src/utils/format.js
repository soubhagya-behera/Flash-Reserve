/* ============================================================
   FlashReserve — Presentation formatters
   Date, time and currency rendering for backend values. Pure
   functions with no component or API knowledge.
   ============================================================ */

const dateFormatter = new Intl.DateTimeFormat(undefined, {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})

const timeFormatter = new Intl.DateTimeFormat(undefined, {
  hour: 'numeric',
  minute: '2-digit',
})

/* The backend stores ticket prices as a bare decimal with no
   currency; FlashReserve settles in INR via Razorpay. */
const priceFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

function toDate(instant) {
  if (!instant) return null
  const date = new Date(instant)
  return Number.isNaN(date.getTime()) ? null : date
}

export function formatEventDate(instant) {
  const date = toDate(instant)
  return date ? dateFormatter.format(date) : ''
}

export function formatEventTime(instant) {
  const date = toDate(instant)
  return date ? timeFormatter.format(date) : ''
}

/** Returns a formatted price string, or null when unset. */
export function formatTicketPrice(price) {
  if (price === null || price === undefined) return null
  const value = Number(price)
  return Number.isFinite(value) ? priceFormatter.format(value) : null
}

/** Formats remaining hold time in whole seconds as m:ss (e.g. 4:05). */
export function formatRemainingSeconds(totalSeconds) {
  const safe = Math.max(0, Math.floor(totalSeconds ?? 0))
  const minutes = Math.floor(safe / 60)
  const seconds = safe % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}
