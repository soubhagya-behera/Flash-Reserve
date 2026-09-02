import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import Alert from '../ui/Alert.jsx'
import Button from '../ui/Button.jsx'
import { useAuth } from '../../auth/authContext.js'
import { usePayment } from '../../hooks/usePayment.js'
import { useCountdown } from '../../hooks/useCountdown.js'
import {
  formatEventDate,
  formatEventTime,
  formatRemainingSeconds,
} from '../../utils/format.js'
import * as bookingService from '../../services/bookingService.js'

/**
 * The dashboard's prominent "payment needed" card for one PENDING
 * booking. It drives the SAME payment flow as My Bookings — the
 * existing usePayment hook (initiate → Razorpay Checkout → server
 * verification) — and refreshes the record from the backend after
 * every settled attempt. The countdown is display-only, exactly like
 * BookingCard's; the backend remains the only authority on state.
 */
export default function PendingPaymentCard({ booking, onBookingUpdated }) {
  const { user } = useAuth()
  const remainingSeconds = useCountdown(booking.expiresAt)

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

  const holdLive = remainingSeconds > 0

  return (
    <article className="pending-card fr-surface-elevated">
      <div className="pending-card__top">
        <span className="pending-card__label">Payment needed</span>
        <span className="pending-card__seat">Seat {booking.seatNumber}</span>
      </div>

      <h3 className="pending-card__event">
        <Link to={`/events/${booking.eventId}`} className="pending-card__event-link">
          {booking.eventName}
        </Link>
      </h3>
      <p className="pending-card__venue">{booking.eventVenue}</p>
      <p className="pending-card__when">
        {formatEventDate(booking.eventDate)} · {formatEventTime(booking.eventDate)}
      </p>

      {holdLive ? (
        <>
          <p
            className="pending-card__countdown"
            role="timer"
            aria-live="off"
            aria-label={`Hold expires in ${formatRemainingSeconds(remainingSeconds)}`}
          >
            <span aria-hidden="true">⏳</span> Seat held for{' '}
            <strong>{formatRemainingSeconds(remainingSeconds)}</strong>
          </p>
          <Button onClick={startPayment} disabled={paying} aria-busy={paying}>
            {paying ? 'Preparing secure checkout…' : 'Complete payment'}
          </Button>
        </>
      ) : (
        <p className="pending-card__expired fr-small">
          The hold window has passed. The server will release this seat
          automatically.
        </p>
      )}

      {error && <Alert>{error}</Alert>}
      {notice && <Alert tone="info">{notice}</Alert>}
    </article>
  )
}
