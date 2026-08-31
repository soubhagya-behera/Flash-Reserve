/* ============================================================
   FlashReserve — Token persistence
   Centralized access to the stored auth record { token, user }.
   The backend issues a JWT immediately on register/login and has
   no refresh endpoint, so a single localStorage record is all the
   persistence that is actually required. Replace this module's
   internals if the strategy evolves — nothing else changes.
   ============================================================ */

const STORAGE_KEY = 'flashreserve.auth'

export function loadAuth() {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed?.token) return null
    return parsed
  } catch {
    return null
  }
}

export function saveAuth({ token, user }) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify({ token, user }))
}

export function clearAuth() {
  window.localStorage.removeItem(STORAGE_KEY)
}

export function getToken() {
  return loadAuth()?.token ?? null
}
