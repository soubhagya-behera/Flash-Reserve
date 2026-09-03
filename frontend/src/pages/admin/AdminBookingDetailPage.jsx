import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import StatusBadge from '../../components/admin/StatusBadge.jsx'
import Button from '../../components/ui/Button.jsx'
import { ApiError } from '../../services/apiClient.js'
import * as bookingService from '../../services/bookingService.js'
import {
  formatEventDate,
  formatEventTime,
  formatTicketPrice,
} from '../../utils/format.js'
import './admin.css'

const GENERIC_ERROR = 'Something went wrong while loading this booking. Please try again.'

/** "1 Jun 2027, 6:00 PM" style timestamp from a real instant. */
function formatWhen(instant) {
  const date = formatEventDate(instant)
  const time = formatEventTime(instant)
  if (!date) return '—'
  return time ? `${date}, ${time}` : date
}

/**
 * Admin detail view for a single booking, fetched from the ADMIN
 * endpoint so every booking is reachable regardless of owner or
 * status. Strictly read-only — the backend exposes no admin booking
 * mutations and none are offered here.
 */
export default function AdminBookingDetailPage() {
  const { bookingId } = useParams()
  const [booking, setBooking] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [resolvedAttempt, setResolvedAttempt] = useState(-1)
  const [outcome, setOutcome] = useState(null) // null | 'not-found' | 'error'
  const [loadError, setLoadError] = useState(null)

  // Derived, never set synchronously inside the effect: a fetch is
  // in flight while its attempt has not resolved yet.
  const loading = resolvedAttempt !== attempt
  const notFound = !loading && outcome === 'not-found'

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    bookingService
      .getAdminBooking(bookingId, { signal: controller.signal })
      .then((result) => {
        if (!active) return
        setBooking(result)
        setLoadError(null)
        setOutcome(null)
        setResolvedAttempt(attempt)
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
        if (error instanceof ApiError && error.status === 404) {
          setOutcome('not-found')
        } else {
          setLoadError(error instanceof ApiError ? error.message : GENERIC_ERROR)
          setOutcome('error')
        }
        setResolvedAttempt(attempt)
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [bookingId, attempt])

  if (loading) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <p className="admin__sr" role="status">Loading booking…</p>
          <div className="admin-detail-card fr-surface-elevated">
            <div className="admin-form__skeleton" aria-hidden="true">
              {Array.from({ length: 6 }, (_, index) => (
                <span key={index} className="admin-form__skeleton-line" />
              ))}
            </div>
          </div>
        </div>
      </main>
    )
  }

  if (notFound) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <div className="admin__state fr-surface-gradient">
            <h1 className="fr-heading">Booking not found</h1>
            <p className="fr-small admin__state-detail">
              No booking exists with this id. It may have been a mistyped or
              stale link.
            </p>
            <Link to="/admin/bookings" className="fr-btn fr-btn--primary">
              Back to all bookings
            </Link>
          </div>
        </div>
      </main>
    )
  }

  if (loadError || !booking) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <div className="admin__state fr-surface-gradient">
            <h1 className="fr-heading">We couldn&apos;t load this booking</h1>
            <p className="fr-small admin__state-detail">{loadError ?? GENERIC_ERROR}</p>
            <Button onClick={() => setAttempt((current) => current + 1)}>Try again</Button>
          </div>
        </div>
      </main>
    )
  }

  const payment = booking.payment

  return (
    <main id="main" className="admin fr-anim-fade-in">
      <div className="fr-container admin-detail-container">
        <Link to="/admin/bookings" className="admin__back">
          <span aria-hidden="true">←</span> Back to all bookings
        </Link>

        <header className="admin-detail-head">
          <div>
            <p className="admin__eyebrow">FlashReserve · Administration</p>
            <h1 className="fr-heading admin-detail-title">{booking.eventName}</h1>
            <p className="admin-detail-meta">
              <StatusBadge status={booking.status} />
              <span className="fr-caption">
                Booked {formatEventDate(booking.createdAt) || '—'}
              </span>
            </p>
          </div>
        </header>

        <div className="admin-detail-card fr-surface-elevated fr-anim-fade-up">
          <dl className="admin-detail-grid">
            <div className="admin-detail-field">
              <dt>Seat</dt>
              <dd>{booking.seatNumber}</dd>
            </div>

            <div className="admin-detail-field">
              <dt>Event date</dt>
              <dd>
                {formatEventDate(booking.eventDate)}
                {formatEventTime(booking.eventDate)
                  ? ` · ${formatEventTime(booking.eventDate)}`
                  : ''}
              </dd>
            </div>

            <div className="admin-detail-field">
              <dt>Venue</dt>
              <dd>{booking.venue}</dd>
            </div>

            <div className="admin-detail-field">
              <dt>Expires at</dt>
              <dd>{booking.expiresAt ? formatWhen(booking.expiresAt) : '—'}</dd>
            </div>

            <div className="admin-detail-field">
              <dt>Booker</dt>
              <dd>{booking.bookerName}</dd>
            </div>

            <div className="admin-detail-field">
              <dt>Booker email</dt>
              <dd>{booking.bookerEmail}</dd>
            </div>

            <div className="admin-detail-field">
              <dt>Created</dt>
              <dd>{formatWhen(booking.createdAt)}</dd>
            </div>

            <div className="admin-detail-field">
              <dt>Last updated</dt>
              <dd>{formatWhen(booking.updatedAt)}</dd>
            </div>

            <div className="admin-detail-field admin-detail-field--wide admin-detail-field--mono">
              <dt>Booking ID</dt>
              <dd>
                <code>{booking.bookingId}</code>
              </dd>
            </div>
          </dl>
        </div>

        <div className="admin-detail-card fr-surface-elevated fr-anim-fade-up">
          <h2 className="fr-subheading">Payment</h2>
          {payment ? (
            <dl className="admin-detail-grid">
              <div className="admin-detail-field">
                <dt>Payment status</dt>
                <dd>{payment.paymentStatus}</dd>
              </div>

              <div className="admin-detail-field">
                <dt>Amount</dt>
                <dd>{formatTicketPrice(payment.amount) ?? '—'}</dd>
              </div>

              <div className="admin-detail-field">
                <dt>Payment reference</dt>
                <dd>{payment.paymentReference}</dd>
              </div>

              {payment.razorpayPaymentId && (
                <div className="admin-detail-field admin-detail-field--mono">
                  <dt>Razorpay payment ID</dt>
                  <dd>
                    <code>{payment.razorpayPaymentId}</code>
                  </dd>
                </div>
              )}
            </dl>
          ) : (
            <p className="fr-small">No payment initiated for this booking yet.</p>
          )}
        </div>
      </div>
    </main>
  )
}