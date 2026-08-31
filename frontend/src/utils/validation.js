/* ============================================================
   FlashReserve — Client-side validation helpers
   Mirror the backend DTO constraints (RegisterRequest /
   LoginRequest) so obvious mistakes never leave the browser.
   Every function returns an error message string, or null if OK.
   ============================================================ */

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export function validateName(value) {
  const trimmed = value.trim()
  if (!trimmed) return 'Name is required'
  if (trimmed.length > 100) return 'Name must not exceed 100 characters'
  return null
}

export function validateEmail(value) {
  const trimmed = value.trim()
  if (!trimmed) return 'Email is required'
  if (!EMAIL_PATTERN.test(trimmed)) return 'Enter a valid email address'
  return null
}

export function validatePassword(value, { requireLength = true } = {}) {
  if (!value) return 'Password is required'
  if (requireLength && (value.length < 8 || value.length > 72)) {
    return 'Password must be between 8 and 72 characters'
  }
  return null
}

export function validateRequired(value, label) {
  return value.trim() ? null : `${label} is required`
}
