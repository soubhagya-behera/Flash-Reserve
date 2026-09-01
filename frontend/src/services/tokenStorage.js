/* ============================================================
   FlashReserve — Token persistence
   Centralized access to the stored auth record { token, user,
   expiresAt }. The backend issues a JWT immediately on register/
   login and has no refresh endpoint, so a single localStorage
   record is all the persistence that is actually required.
   Replace this module's internals if the strategy evolves —
   nothing else changes.
   ============================================================ */

const STORAGE_KEY = 'flashreserve.auth'

export function loadAuth() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed?.token || isStale(parsed)) return null
    return parsed
  } catch {
    return null
  }
}

/* A persisted session must never look signed in after its JWT has
   died: an expired token produces 401s that the UI would otherwise
   misread as "authentication required while signed in". Records
   saved before expiry was tracked carry no expiresAt at all and
   are treated as stale so they self-heal on the next sign-in. */
function isStale(record) {
  if (!('expiresAt' in record) || record.expiresAt === undefined) return true
  return record.expiresAt !== null && record.expiresAt <= Date.now()
}

export function saveAuth({ token, user, expiresAt }) {
  window.localStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({ token, user, expiresAt }),
  )
}

export function clearAuth() {
  window.localStorage.removeItem(STORAGE_KEY)
}

export function getToken() {
  return loadAuth()?.token ?? null
}
