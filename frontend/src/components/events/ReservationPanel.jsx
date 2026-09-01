import Button from '../ui/Button.jsx'
import { useCountdown } from '../../hooks/useCountdown.js'
import {
  formatEventDate,
  formatEventTime,
  formatRemainingSeconds,
} from '../../utils/format.js'
import './reservation-panel.css'

/**
 * Confirmation panel for a successful reservation hold. Shows only
 * fields the backend actually returned plus a live countdown driven
 * by the server-provided `expiresAt`. PENDING means the booking is
 * not confirmed and no payment has been made yet — the copy must
 * never claim otherwise.
 */
export default function ReservationPanel({
  reservation,
  eventName,
  onRefreshAvailability,
  onReserveAnotherSeat,
}) {
  const remainingSeconds = useCountdown(reservation.expiresAt)
  const expired = remainingSeconds === 0

  return (
    <div className="reservation-panel fr-anim-fade-up">
      <div className="reservation-panel__head">
        <div>
          <p className="reservation-panel__eyebrow">Reservation pending</p>
          <h3 className="reservation-panel__title">Seat held</h3>
        </div>
        <span className="reservation-panel__chip">{reservation.status}</span>
      </div>

      {expired ? (
        <div className="reservation-panel__expired">
          <p className="reservation-panel__expired-title">
            Your hold has expired.
          </p>
          <p className="fr-small">
            The seat was not paid for in time, so the server will release it
            automatically. Refresh to see the latest availability.
          </p>
          <Button onClick={onRefreshAvailability}>Refresh availability</Button>
        </div>
      ) : (
        <>
          <p className="reservation-panel__message fr-small">
            Your seat is temporarily held. Complete payment before the hold
            expires to keep it.
          </p>

          <dl className="reservation-panel__details">
            <div className="reservation-panel__detail">
              <dt>Seat</dt>
              <dd>{reservation.seatNumber}</dd>
            </div>
            <div className="reservation-panel__detail">
              <dt>Event</dt>
              <dd>{eventName}</dd>
            </div>
            <div className="reservation-panel__detail reservation-panel__detail--wide">
              <dt>Booking ID</dt>
              <dd className="reservation-panel__booking-id">
                {reservation.bookingId}
              </dd>
            </div>
            <div className="reservation-panel__detail">
              <dt>Held until</dt>
              <dd>
                {formatEventDate(reservation.expiresAt)} ·{' '}
                {formatEventTime(reservation.expiresAt)}
              </dd>
            </div>
          </dl>

          <p
            className="reservation-panel__countdown"
            role="timer"
            aria-live="off"
            aria-label={`Hold expires in ${formatRemainingSeconds(remainingSeconds)}`}
          >
            <span className="reservation-panel__countdown-value">
              {formatRemainingSeconds(remainingSeconds)}
            </span>
            <span className="reservation-panel__countdown-label">
              hold remaining
            </span>
          </p>

          <button
            type="button"
            className="reservation-panel__link"
            onClick={onReserveAnotherSeat}
          >
            Reserve a different seat
          </button>
        </>
      )}
    </div>
  )
}
