import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import BookingCard from '../components/bookings/BookingCard.jsx'
import PayableBookingCard from '../components/bookings/PayableBookingCard.jsx'
import OverviewCards from '../components/dashboard/OverviewCards.jsx'
import PendingPaymentCard from '../components/dashboard/PendingPaymentCard.jsx'
import Button from '../components/ui/Button.jsx'
import { useAuth } from '../auth/authContext.js'
import { ApiError } from '../services/apiClient.js'
import * as bookingService from '../services/bookingService.js'
import './dashboard.css'

/* Large enough that a real user's bookings are fetched in one request
   in practice; the loader walks further pages when they exist so the
   overview counts are exact, never sampled. */
const PAGE_SIZE = 50
const UPCOMING_COUNT = 2
const RECENT_COUNT = 4
const GENERIC_ERROR =
  'Something went wrong while loading your dashboard. Please try again.'

function greetingFor(date) {
  const hour = date.getHours()
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

function eventTimestamp(booking) {
  const time = Date.parse(booking.eventDate)
  return Number.isNaN(time) ? 0 : time
}

/* Walks every page of the caller's bookings so derived counts cover
   the whole list, not just its first slice. Ownership comes from the
   JWT on the backend — the frontend never sends a userId. */
async function loadAllBookings(signal) {
  const first = await bookingService.listOwnBookings({
    page: 0,
    size: PAGE_SIZE,
    signal,
  })
  const all = [...(first.content ?? [])]
  const totalPages = first.page?.totalPages ?? 1
  const totalElements = first.page?.totalElements ?? all.length

  for (let page = 1; page < totalPages && all.length < totalElements; page += 1) {
    const next = await bookingService.listOwnBookings({ page, size: PAGE_SIZE, signal })
    all.push(...(next.content ?? []))
  }

  return { bookings: all, totalElements }
}

/**
 * The authenticated user's dashboard. This page owns all data loading
 * and derives every number from the real backend booking list — no
 * fabricated metrics. Pending bookings are surfaced prominently with
 * the existing payment flow; everything else links into My Bookings.
 */
export default function DashboardPage() {
  const { user, isAuthenticated } = useAuth()
  const [bookings, setBookings] = useState(null)
  const [totalElements, setTotalElements] = useState(0)
  const [fetchedAt, setFetchedAt] = useState(null)
  const [errorMessage, setErrorMessage] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [resolvedAttempt, setResolvedAttempt] = useState(-1)

  /* Stale while a (re)fetch is in flight — derived, so no setState
     has to run synchronously inside the effect. */
  const isStale = resolvedAttempt !== attempt
  const hasError = !isStale && errorMessage !== null

  useEffect(() => {
    if (!isAuthenticated) return undefined
    const controller = new AbortController()
    let active = true

    loadAllBookings(controller.signal)
      .then((result) => {
        if (!active) return
        setBookings(result.bookings)
        setTotalElements(result.totalElements)
        setFetchedAt(Date.now())
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
  }, [isAuthenticated, attempt])

  /* A payment attempt settles with the backend's own state; swap the
     refreshed record in and the derived views re-sort themselves. */
  const handleBookingUpdated = useCallback((updated) => {
    setBookings((current) =>
      current
        ? current.map((booking) =>
            booking.bookingId === updated.bookingId ? updated : booking,
          )
        : current,
    )
  }, [])

  const firstName = user?.name?.split(' ')[0] ?? 'there'

  const views = useMemo(() => {
    if (!bookings) return { upcoming: [], pending: [], recent: [], confirmed: 0 }
    const now = fetchedAt ?? 0
    const pending = bookings
      .filter((booking) => booking.status === 'PENDING')
      .sort(
        (a, b) =>
          (Date.parse(a.expiresAt) || 0) - (Date.parse(b.expiresAt) || 0),
      )
    const upcoming = bookings
      .filter(
        (booking) =>
          booking.status === 'CONFIRMED' && eventTimestamp(booking) > now,
      )
      .sort((a, b) => eventTimestamp(a) - eventTimestamp(b))
    const recent = bookings
      .filter((booking) => booking.status !== 'PENDING')
      .slice(0, RECENT_COUNT)
    const confirmed = bookings.filter(
      (booking) => booking.status === 'CONFIRMED',
    ).length
    return { upcoming, pending, recent, confirmed }
  }, [bookings, fetchedAt])

  if (isStale) {
    return (
      <main id="main" className="dashboard fr-anim-fade-in">
        <div className="fr-container">
          <HeaderBlock firstName={firstName} />
          <OverviewCards counts={{}} loading />
          <div className="dashboard__skeleton fr-surface" aria-hidden="true">
            <span className="dashboard__line dashboard__line--chip" />
            <span className="dashboard__line dashboard__line--title" />
            <span className="dashboard__line dashboard__line--meta dashboard__line--short" />
          </div>
          <p className="dashboard__sr" role="status">
            Loading your dashboard…
          </p>
        </div>
      </main>
    )
  }

  if (hasError) {
    return (
      <main id="main" className="dashboard fr-anim-fade-in">
        <div className="fr-container">
          <HeaderBlock firstName={firstName} />
          <div className="dashboard__state fr-surface-gradient">
            <h2 className="fr-subheading">We couldn&apos;t load your dashboard</h2>
            <p className="fr-small dashboard__state-detail">{errorMessage}</p>
            <Button onClick={() => setAttempt((current) => current + 1)}>
              Try again
            </Button>
          </div>
        </div>
      </main>
    )
  }

  return (
    <main id="main" className="dashboard fr-anim-fade-in">
      <div className="fr-container">
        <HeaderBlock firstName={firstName} />

        <OverviewCards
          counts={{
            upcoming: views.upcoming.length,
            pending: views.pending.length,
            confirmed: views.confirmed,
            total: totalElements,
          }}
        />

        {totalElements === 0 && (
          <div className="dashboard__state fr-surface-gradient">
            <h2 className="fr-subheading">No bookings yet.</h2>
            <p className="fr-small dashboard__state-detail">
              Reserve a seat at one of our events and your holds,
              confirmations and upcoming shows will all live here.
            </p>
            <Link to="/events" className="fr-btn fr-btn--primary">
              Explore events
            </Link>
          </div>
        )}

        {views.pending.length > 0 && (
          <section className="dashboard__section" aria-labelledby="dashboard-pending">
            <h2 className="fr-subheading" id="dashboard-pending">
              Needs your attention
            </h2>
            <p className="dashboard__section-lead">
              A seat is on hold for you. Complete payment before the hold
              expires to keep it.
            </p>
            <div className="dashboard__pending-grid">
              {views.pending.map((booking) => (
                <PendingPaymentCard
                  key={booking.bookingId}
                  booking={booking}
                  onBookingUpdated={handleBookingUpdated}
                />
              ))}
            </div>
          </section>
        )}

        {views.upcoming.length > 0 && (
          <section className="dashboard__section" aria-labelledby="dashboard-upcoming">
            <h2 className="fr-subheading" id="dashboard-upcoming">
              Upcoming
            </h2>
            <div className="dashboard__upcoming-grid">
              {views.upcoming.slice(0, UPCOMING_COUNT).map((booking) => (
                <BookingCard key={booking.bookingId} booking={booking} />
              ))}
            </div>
          </section>
        )}

        {views.recent.length > 0 && (
          <section className="dashboard__section" aria-labelledby="dashboard-recent">
            <div className="dashboard__section-head">
              <h2 className="fr-subheading" id="dashboard-recent">
                Recent bookings
              </h2>
              <Link to="/bookings" className="dashboard__link">
                View all bookings <span aria-hidden="true">→</span>
              </Link>
            </div>
            <div className="dashboard__recent-list">
              {views.recent.map((booking) => (
                <PayableBookingCard
                  key={booking.bookingId}
                  booking={booking}
                  onBookingUpdated={handleBookingUpdated}
                />
              ))}
            </div>
          </section>
        )}

        {totalElements > 0 && (
          <div className="dashboard__explore">
            <Link to="/events" className="fr-btn fr-btn--primary">
              Explore events
            </Link>
            <Link to="/bookings" className="fr-btn fr-btn--ghost">
              View all bookings
            </Link>
          </div>
        )}
      </div>
    </main>
  )
}

function HeaderBlock({ firstName }) {
  return (
    <header className="dashboard__header">
      <p className="dashboard__eyebrow">FlashReserve · Your account</p>
      <h1 className="fr-heading dashboard__title">
        {greetingFor(new Date())}, {firstName}
      </h1>
      <p className="dashboard__lead">
        Your holds, confirmations and upcoming shows — exactly as the server
        reports them.
      </p>
    </header>
  )
}
