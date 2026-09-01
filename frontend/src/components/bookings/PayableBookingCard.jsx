import { useCallback } from 'react'
import BookingCard from './BookingCard.jsx'
import { usePayment } from '../../hooks/usePayment.js'
import { useAuth } from '../../auth/authContext.js'
import * as bookingService from '../../services/bookingService.js'
import './booking-card.css'

/**
 * A bookings-list entry that owns its own payment flow: the
 * usePayment hook drives initiate → Razorpay Checkout → server
 * verification for this one booking, and the record is refreshed
 * from the backend after every settled attempt so the list never
 * shows an invented status. Purely presentational concerns stay
 * in BookingCard.
 */
export default function PayableBookingCard({ booking, onCancel, cancelling, onBookingUpdated }) {
  const { user } = useAuth()

  const refreshBooking = useCallback(async () => {
    try {
      const updated = await bookingService.getBooking(booking.bookingId)
      onBookingUpdated?.(updated)
    } catch {
      // Keep the last known backend state; the next action re-fetches.
    }
  }, [booking.bookingId, onBookingUpdated])

  const { startPayment, paying, notice, error } = usePayment({
    bookingId: booking.bookingId,
    description: `Seat ${booking.seatNumber} · ${booking.eventName}`,
    prefillEmail: user?.email,
    onRefresh: refreshBooking,
  })

  return (
    <BookingCard
      booking={booking}
      onCancel={onCancel}
      cancelling={cancelling}
      onPay={startPayment}
      paying={paying}
      paymentNotice={notice}
      paymentError={error}
    />
  )
}
