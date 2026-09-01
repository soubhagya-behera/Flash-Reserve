/* ============================================================
   FlashReserve — Hold countdown hook
   Ticks a whole-second countdown toward a backend-provided ISO
   timestamp (Instant). The frontend timer is display-only: it
   never releases a seat and never fakes a booking state.
   ============================================================ */

import { useEffect, useState } from 'react'

function remainingSecondsFor(expiresAt) {
  if (!expiresAt) return 0
  const target = Date.parse(expiresAt)
  if (Number.isNaN(target)) return 0
  return Math.max(0, Math.floor((target - Date.now()) / 1000))
}

/**
 * Seconds remaining until `expiresAt`, recomputed every second.
 * Stops at zero, never goes negative, and stops its interval once
 * expired so no timer keeps running behind a finished hold.
 */
export function useCountdown(expiresAt) {
  const [remainingSeconds, setRemainingSeconds] = useState(() =>
    remainingSecondsFor(expiresAt),
  )
  const [prevExpiresAt, setPrevExpiresAt] = useState(expiresAt)

  // Adjust state during render when the deadline changes — avoids a
  // cascading effect render and keeps the countdown in sync.
  if (prevExpiresAt !== expiresAt) {
    setPrevExpiresAt(expiresAt)
    setRemainingSeconds(remainingSecondsFor(expiresAt))
  }

  useEffect(() => {
    if (remainingSecondsFor(expiresAt) === 0) return undefined

    const interval = setInterval(() => {
      const remaining = remainingSecondsFor(expiresAt)
      setRemainingSeconds(remaining)
      if (remaining === 0) clearInterval(interval)
    }, 1000)

    return () => clearInterval(interval)
  }, [expiresAt])

  return remainingSeconds
}
