/* ============================================================
   FlashReserve — Central API client
   Single place where fetch configuration, JSON handling, bearer
   attachment and backend ApiError normalization live. React
   components never call fetch directly.
   ============================================================ */

import { getToken } from './tokenStorage.js'

/* Base URL comes from the environment. Empty string = same origin,
   which pairs with the Vite dev proxy (/api -> backend) and keeps
   production configurable without hardcoded URLs in source. */
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

/* Normalized frontend error. `status` 0 means the request never
   reached the backend (network/DNS). fieldErrors mirrors the
   backend ApiError.fieldErrors map for form consumption. */
export class ApiError extends Error {
  constructor(status, message, { fieldErrors = null, retryAfterSeconds = null } = {}) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.fieldErrors = fieldErrors
    this.retryAfterSeconds = retryAfterSeconds
  }
}

const FALLBACK_MESSAGES = {
  400: 'The request was invalid. Please check your input and try again.',
  401: 'Authentication failed. Please sign in again.',
  403: 'You do not have permission to do that.',
  404: 'What you are looking for could not be found.',
  409: 'That conflicts with an existing item.',
  429: 'Too many requests. Please slow down and try again shortly.',
  503: 'The service is temporarily unavailable. Please try again soon.',
}

/**
 * Performs a JSON API request and resolves with the parsed body.
 * Throws a normalized ApiError for every non-2xx outcome.
 */
export async function apiRequest(path, { method = 'GET', body, signal } = {}) {
  let response

  try {
    response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: buildHeaders(body),
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (networkError) {
    if (networkError instanceof DOMException && networkError.name === 'AbortError') {
      throw networkError
    }
    throw new ApiError(0, 'Could not reach FlashReserve. Check your connection and try again.')
  }

  const payload = await readJson(response)

  if (!response.ok) {
    throw normalizeError(response, payload)
  }

  return payload
}

function buildHeaders(body) {
  const headers = {}
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  return headers
}

async function readJson(response) {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return null // Non-JSON body (e.g. a gateway HTML error page).
  }
}

function normalizeError(response, payload) {
  const retryAfterSeconds = parseRetryAfter(response)
  const baseMessage =
    payload?.message ?? FALLBACK_MESSAGES[response.status] ?? 'Something went wrong. Please try again.'

  return new ApiError(response.status, withRetryHint(baseMessage, retryAfterSeconds), {
    fieldErrors: payload?.fieldErrors ?? null,
    retryAfterSeconds,
  })
}

function parseRetryAfter(response) {
  if (response.status !== 429) return null
  const seconds = Number(response.headers.get('Retry-After'))
  return Number.isFinite(seconds) && seconds > 0 ? seconds : null
}

function withRetryHint(message, retryAfterSeconds) {
  if (!retryAfterSeconds) return message
  const delay =
    retryAfterSeconds >= 60
      ? `${Math.ceil(retryAfterSeconds / 60)} min`
      : `${retryAfterSeconds} sec`
  return `${message} Try again in about ${delay}.`
}

/* Single reusable adapter for form pages: turns any thrown error
   into { message, fieldErrors } so pages never duplicate the logic. */
export function toFormErrorState(error) {
  if (error instanceof ApiError) {
    return { message: error.message, fieldErrors: error.fieldErrors ?? {} }
  }
  return { message: 'Something went wrong. Please try again.', fieldErrors: {} }
}
