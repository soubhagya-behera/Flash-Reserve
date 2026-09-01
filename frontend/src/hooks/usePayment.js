/* ============================================================
   FlashReserve — Payment flow hook
   Orchestrates one checkout attempt for a booking: initiate →
   open Razorpay Checkout → server-side verification → refresh
   the booking from the backend. The backend is the only
   authority on payment outcomes; the frontend never marks a
   booking confirmed and never retries POSTs automatically.
   ============================================================ */

import { useCallback, useRef, useState } from 'react'
import { ApiError } from '../services/apiClient.js'
import * as paymentService from '../services/paymentService.js'
import {
  CheckoutClosedError,
  openRazorpayCheckout,
} from '../services/razorpayCheckout.js'

/**
 * @param {{ bookingId: string, description?: string, prefillEmail?: string,
 *          onRefresh?: (outcome) => Promise<void> }} options
 * `onRefresh` is awaited after every settled attempt (confirmed,
 * dismissed or failed) so the page always re-reads the real
 * backend state instead of trusting frontend transitions.
 */
export function usePayment({ bookingId, description, prefillEmail, onRefresh }) {
  const [paying, setPaying] = useState(false)
  const [notice, setNotice] = useState(null) // info-level (checkout closed, hold intact)
  const [error, setError] = useState(null)
  const inFlight = useRef(false)

  const startPayment = useCallback(async () => {
    if (inFlight.current) return
    inFlight.current = true
    setPaying(true)
    setNotice(null)
    setError(null)

    let outcome
    try {
      // 1. Server creates (or idempotently reuses) the TEST order.
      const initiation = await paymentService.initiatePayment(bookingId)

      try {
        // 2. Official Razorpay Checkout, opened for that order.
        const checkout = await openRazorpayCheckout({
          keyId: initiation.razorpayKeyId,
          orderId: initiation.razorpayOrderId,
          description,
          prefillEmail,
        })

        // 3. Server-side signature verification is the only
        //    authority that confirms the booking.
        const confirmation = await paymentService.verifyPayment(bookingId, checkout)
        outcome = { confirmed: true, confirmation }
      } catch (checkoutError) {
        if (checkoutError instanceof CheckoutClosedError) {
          // No payment completed: the backend hold is untouched.
          setNotice(
            'The checkout was closed before payment completed. Your booking is still on hold — complete payment before the hold expires.',
          )
          outcome = { dismissed: true }
        } else {
          throw checkoutError
        }
      }
    } catch (paymentError) {
      setError(
        paymentError instanceof ApiError
          ? paymentError.message
          : 'Payment could not be completed. Please try again.',
      )
      outcome = { error: true }
    } finally {
      inFlight.current = false
      setPaying(false)
    }

    await onRefresh?.(outcome)
    return outcome
  }, [bookingId, description, prefillEmail, onRefresh])

  return { startPayment, paying, notice, error }
}
