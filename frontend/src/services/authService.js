/* ============================================================
   FlashReserve — Authentication service
   Thin wrappers over the API client for the two auth endpoints
   that exist in the backend. Contracts mirror the backend DTOs:
   - POST /api/auth/register { name, email, password } -> 201 AuthResponse
   - POST /api/auth/login    { email, password }        -> 200 AuthResponse
   AuthResponse: { accessToken, tokenType, expiresIn, user }
   ============================================================ */

import { apiRequest } from './apiClient.js'

function toAuthResult(data) {
  return {
    token: data.accessToken,
    user: data.user,
    // expiresIn is the JWT lifetime in seconds (backend AuthResponse);
    // persisting the absolute expiry lets loadAuth reject dead tokens.
    expiresAt:
      Number.isFinite(data.expiresIn) && data.expiresIn > 0
        ? Date.now() + data.expiresIn * 1000
        : null,
  }
}

export function sendRegisterOtp({ name, email }) {
  return apiRequest('/api/auth/register/send-otp', {
    method: 'POST',
    body: { name, email },
  })
}

export function verifyRegisterOtp({ email, code }) {
  return apiRequest('/api/auth/register/verify-otp', {
    method: 'POST',
    body: { email, code },
  })
}

export function register({ name, email, password }) {
  return apiRequest('/api/auth/register', {
    method: 'POST',
    body: { name, email, password },
  }).then(toAuthResult)
}

export function login({ email, password }) {
  return apiRequest('/api/auth/login', {
    method: 'POST',
    body: { email, password },
  }).then(toAuthResult)
}
