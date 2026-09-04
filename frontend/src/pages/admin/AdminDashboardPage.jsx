import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import StatusBadge from '../../components/admin/StatusBadge.jsx'
import Button from '../../components/ui/Button.jsx'
import { ApiError } from '../../services/apiClient.js'
import { getAdminDashboard } from '../../services/adminService.js'
import {
  formatEventDate,
  formatEventTime,
  formatTicketPrice,
} from '../../utils/format.js'
import './admin.css'

const GENERIC_ERROR = 'Something went wrong while loading the dashboard. Please try again.'

/** Short booking label: the leading chunk of the backend UUID (as in the tables). */
function bookingLabel(bookingId) {
  return `#${bookingId.slice(0, 8)}`
}

/** "1 Jun 2027, 6:00 PM" style timestamp from a real instant. */
function formatWhen(instant) {
  const date = formatEventDate(instant)
  const time = formatEventTime(instant)
  return time ? `${date}, ${time}` : date || '—'
}

function MetricCard({ value, label, accent = false }) {
  return (
    <div className="admin-metric fr-surface-elevated">
      <p className={`admin-metric__value${accent ? ' admin-metric__value--accent' : ''}`}>
        {value}
      </p>
      <p className="admin-metric__label">{label}</p>
    </div>
  )
}

/**
 * ADMIN dashboard: one read-only snapshot from GET /api/admin/dashboard.
 * Every number comes straight from the backend aggregates — nothing is
 * derived from lists client-side, and recent bookings render from the
 * same response with no follow-up calls. Follows the shared admin page
 * state machine: abortable fetch, skeleton while loading, real error
 * state with retry.
 */
export default function AdminDashboardPage() {
  const [attempt, setAttempt] = useState(0)
  const [resolvedAttempt, setResolvedAttempt] = useState(-1)
  const [data, setData] = useState(null)
  const [errorMessage, setErrorMessage] = useState(null)

  const loading = resolvedAttempt !== attempt

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    getAdminDashboard({ signal: controller.signal })
      .then((result) => {
        if (!active) return
        setData(result)
        setErrorMessage(null)
        setResolvedAttempt(attempt)
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
        setErrorMessage(error instanceof ApiError ? error.message : GENERIC_ERROR)
        setResolvedAttempt(attempt)
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [attempt])

  if (loading) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <p className="admin__sr" role="status">Loading dashboard…</p>
          <div className="admin-metrics">
            {Array.from({ length: 6 }, (_, index) => (
              <div key={index} className="admin-metric fr-surface-elevated">
                <span className="admin-form__skeleton-line admin-metric__skeleton" />
              </div>
            ))}
          </div>
          <div className="admin-detail-card fr-surface-elevated">
            <div className="admin-form__skeleton" aria-hidden="true">
              {Array.from({ length: 4 }, (_, index) => (
                <span key={index} className="admin-form__skeleton-line" />
              ))}
            </div>
          </div>
        </div>
      </main>
    )
  }

  if (errorMessage) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <div className="admin__state fr-surface-gradient">
            <h1 className="fr-heading">We couldn&apos;t load the dashboard</h1>
            <p className="fr-small admin__state-detail">{errorMessage}</p>
            <Button onClick={() => setAttempt((current) => current + 1)}>Try again</Button>
          </div>
        </div>
      </main>
    )
  }

  const events = data?.events ?? {}
  const bookings = data?.bookings ?? {}
  const revenue = data?.revenue ?? {}
  const seats = data?.seats ?? {}
  const recentBookings = data?.recentBookings ?? []

  return (
    <main id="main" className="admin fr-anim-fade-in">
      <div className="fr-container">
        <header className="admin__header">
          <div>
            <p className="admin__eyebrow">FlashReserve · Administration</p>
            <h1 className="fr-heading admin__title">Admin Overview</h1>
            <p className="admin__lead">
              Monitor reservation activity, inventory, and confirmed payments.
            </p>
          </div>
        </header>

        <section aria-label="Key metrics">
          <div className="admin-metrics">
            <MetricCard value={events.total ?? 0} label="Total events" />
            <MetricCard value={events.published ?? 0} label="Published" />
            <MetricCard value={events.upcomingPublished ?? 0} label="Upcoming" />
            <MetricCard value={bookings.total ?? 0} label="Total bookings" />
            <MetricCard value={bookings.confirmed ?? 0} label="Confirmed" accent />
            <MetricCard
              value={formatTicketPrice(revenue.confirmedRevenue) ?? '₹0.00'}
              label="Confirmed revenue"
              accent
            />
          </div>
        </section>

        <div className="admin-panels">
          <div className="admin-detail-card fr-surface-elevated fr-anim-fade-up">
            <h2 className="fr-subheading">Booking pipeline</h2>
            <dl className="admin-detail-grid">
              <div className="admin-detail-field">
                <dt>Pending</dt>
                <dd>{bookings.pending ?? 0}</dd>
              </div>
              <div className="admin-detail-field">
                <dt>Expired</dt>
                <dd>{bookings.expired ?? 0}</dd>
              </div>
              <div className="admin-detail-field">
                <dt>Cancelled</dt>
                <dd>{bookings.cancelled ?? 0}</dd>
              </div>
              <div className="admin-detail-field">
                <dt>Confirmed payments</dt>
                <dd>{revenue.confirmedPaymentCount ?? 0}</dd>
              </div>
            </dl>
          </div>

          <div className="admin-detail-card fr-surface-elevated fr-anim-fade-up">
            <h2 className="fr-subheading">Seat inventory</h2>
            <dl className="admin-detail-grid">
              <div className="admin-detail-field">
                <dt>Total seats</dt>
                <dd>{seats.total ?? 0}</dd>
              </div>
              <div className="admin-detail-field">
                <dt>Available</dt>
                <dd>{seats.available ?? 0}</dd>
              </div>
              <div className="admin-detail-field">
                <dt>Held</dt>
                <dd>{seats.held ?? 0}</dd>
              </div>
              <div className="admin-detail-field">
                <dt>Booked</dt>
                <dd>{seats.booked ?? 0}</dd>
              </div>
            </dl>
          </div>

          <div className="admin-detail-card fr-surface-elevated fr-anim-fade-up">
            <h2 className="fr-subheading">Event catalog</h2>
            <dl className="admin-detail-grid">
              <div className="admin-detail-field">
                <dt>Draft</dt>
                <dd>{events.draft ?? 0}</dd>
              </div>
              <div className="admin-detail-field">
                <dt>Cancelled</dt>
                <dd>{events.cancelled ?? 0}</dd>
              </div>
              <div className="admin-detail-field">
                <dt>Completed</dt>
                <dd>{events.completed ?? 0}</dd>
              </div>
            </dl>
          </div>
        </div>

        <section aria-label="Recent bookings">
          <h2 className="fr-subheading admin-section-title">Recent bookings</h2>
          {recentBookings.length === 0 ? (
            <div className="admin__state fr-surface-gradient">
              <h3 className="fr-subheading">No recent bookings</h3>
              <p className="fr-small admin__state-detail">
                Bookings appear here the moment a customer holds a seat.
              </p>
            </div>
          ) : (
            <div className="admin-table-wrap fr-surface">
              <table className="admin-table">
                <caption className="sr-only">The five newest bookings</caption>
                <thead>
                  <tr>
                    <th scope="col">Booking</th>
                    <th scope="col">Event</th>
                    <th scope="col">Seat</th>
                    <th scope="col">Status</th>
                    <th scope="col">Created</th>
                  </tr>
                </thead>
                <tbody>
                  {recentBookings.map((booking) => (
                    <tr key={booking.bookingId}>
                      <th scope="row" className="admin-table__cell" data-label="Booking">
                        <Link
                          className="admin-table__name admin-table__id"
                          to={`/admin/bookings/${booking.bookingId}`}
                          title={booking.bookingId}
                        >
                          {bookingLabel(booking.bookingId)}
                        </Link>
                      </th>
                      <td className="admin-table__cell" data-label="Event">
                        {booking.eventName}
                      </td>
                      <td className="admin-table__cell" data-label="Seat">
                        {booking.seatNumber}
                      </td>
                      <td className="admin-table__cell" data-label="Status">
                        <StatusBadge status={booking.status} />
                      </td>
                      <td className="admin-table__cell" data-label="Created">
                        {formatWhen(booking.createdAt)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      </div>
    </main>
  )
}