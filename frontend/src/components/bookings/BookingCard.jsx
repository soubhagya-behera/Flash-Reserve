import { useState } from 'react'
import { Link } from 'react-router-dom'
import Button from '../ui/Button.jsx'
import { useCountdown } from '../../hooks/useCountdown.js'
import {
  formatEventDate,
  formatEventTime,
  formatRemainingSeconds,
} from '../../utils/format.js'
import './booking-card.css'

/* Backend BookingStatus enum rendered verbatim — the chip always
   carries the actual status the backend reported. */
const STATUS_TONES = {
  PENDING: 'pending',
  CONFIRMED: 'confirmed',
  EXPIRED: 'closed',
  CANCELLED: 'closed',
}

/**
 * Presentation-only booking card. Receives one backend
 * BookingResponse and renders it; it never fetches and holds no
 * booking state. Used by both the bookings list and the detail
 * page (`detailed` shows the full record). Cancellation is a
 * two-step control so a single stray click never cancels.
 */
export default function BookingCard({ booking, detailed = false, onCancel, cancelling = false }) {
  const [confirming, setConfirming] = useState(false)
  const tone = STATUS_TONES[booking.status] ?? 'closed'
  const remainingSeconds = useCountdown(
    booking.status === 'PENDING' ? booking.expiresAt : null,
  )

  const handleCancelClick = () => {
    if (confirming) {
      onCancel(booking.bookingId)
    } else {
      setConfirming(true)
    }
  }

  return (
    <article className={`booking-card fr-surface booking-card--${tone}`}>
      <div className="booking-card__top">
        <span className={`booking-card__status booking-card__status--${tone}`}>
          {booking.status}
        </span>
        <span className="booking-card__seat">Seat {booking.seatNumber}</span>
      </div>

      <h3 className="booking-card__event">
        <Link to={`/events/${booking.eventId}`} className="booking-card__event-link">
          {booking.eventName}
        </Link>
      </h3>
      <p className="booking-card__venue">{booking.eventVenue}</p>
      <p className="booking-card__when">
        {formatEventDate(booking.eventDate)} · {formatEventTime(booking.eventDate)}
      </p>

      {booking.status === 'PENDING' && (
        <div className="booking-card__hold">
          {remainingSeconds > 0 ? (
            <>
              <p
                className="booking-card__countdown"
                role="timer"
                aria-live="off"
                aria-label={`Hold expires in ${formatRemainingSeconds(remainingSeconds)}`}
              >
                <span aria-hidden="true">⏳</span> Hold expires in{' '}
                <strong>{formatRemainingSeconds(remainingSeconds)}</strong>
              </p>
              <p className="booking-card__note fr-small">
                Complete payment before the hold expires to confirm this seat.
              </p>
            </>
          ) : (
            <p className="booking-card__note fr-small">
              The hold window has passed. The server will release this seat
              automatically.
            </p>
          )}
        </div>
      )}

      {booking.status === 'CONFIRMED' && (
        <p className="booking-card__note fr-small">
          Payment verified — your seat is booked.
        </p>
      )}

      {detailed && (
        <dl className="booking-card__details">
          <div>
            <dt>Booking ID</dt>
            <dd className="booking-card__booking-id">{booking.bookingId}</dd>
          </div>
          <div>
            <dt>Booked on</dt>
            <dd>{formatEventDate(booking.createdAt)} · {formatEventTime(booking.createdAt)}</dd>
          </div>
          <div>
            <dt>Last updated</dt>
            <dd>{formatEventDate(booking.updatedAt)} · {formatEventTime(booking.updatedAt)}</dd>
          </div>
          {booking.expiresAt && (
            <div>
              <dt>Hold expired / expires</dt>
              <dd>{formatEventDate(booking.expiresAt)} · {formatEventTime(booking.expiresAt)}</dd>
            </div>
          )}
        </dl>
      )}

      <div className="booking-card__foot">
        {!detailed && (
          <Link to={`/bookings/${booking.bookingId}`} className="booking-card__cta">
            View details
            <span aria-hidden="true">→</span>
          </Link>
        )}

        {booking.status === 'PENDING' && onCancel && (
          <div className="booking-card__cancel">
            {confirming && (
              <p className="booking-card__confirm fr-small" role="note">
                Cancel this booking and release the seat?
              </p>
            )}
            {confirming ? (
              <div className="booking-card__cancel-actions">
                <Button
                  variant="ghost"
                  className="fr-btn--compact"
                  onClick={() => setConfirming(false)}
                  disabled={cancelling}
                >
                  Keep booking
                </Button>
                <Button
                  variant="ghost"
                  className="fr-btn--compact booking-card__cancel-button"
                  onClick={handleCancelClick}
                  disabled={cancelling}
                  aria-busy={cancelling}
                >
                  {cancelling ? 'Cancelling…' : 'Yes, cancel it'}
                </Button>
              </div>
            ) : (
              <Button
                variant="ghost"
                className="fr-btn--compact booking-card__cancel-button"
                onClick={handleCancelClick}
              >
                Cancel booking
              </Button>
            )}
          </div>
        )}
      </div>
    </article>
  )
}
