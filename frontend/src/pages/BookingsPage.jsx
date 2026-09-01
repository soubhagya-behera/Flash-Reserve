import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import Button from '../components/ui/Button.jsx'
import PayableBookingCard from '../components/bookings/PayableBookingCard.jsx'
import Alert from '../components/ui/Alert.jsx'
import { useAuth } from '../auth/authContext.js'
import { ApiError } from '../services/apiClient.js'
import * as bookingService from '../services/bookingService.js'
import './bookings.css'

const PAGE_SIZE = 10
const GENERIC_ERROR = 'Something went wrong while loading your bookings. Please try again.'

/**
 * The authenticated user's own bookings, fetched from the real
 * backend (ownership comes from the JWT — the frontend never sends
 * a userId). This page owns all data loading and the cancel
 * action; BookingCard stays purely presentational. After a
 * cancellation the record is replaced with the backend's own
 * response — state is never faked locally.
 */
export default function BookingsPage() {
  const { isAuthenticated } = useAuth()
  const [page, setPage] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const [resolved, setResolved] = useState({ page: -1, attempt: -1 })
  const [bookings, setBookings] = useState([])
  const [totalPages, setTotalPages] = useState(1)
  const [errorMessage, setErrorMessage] = useState(null)
  const [actionError, setActionError] = useState(null)
  const [cancellingId, setCancellingId] = useState(null)

  const isStale = resolved.page !== page || resolved.attempt !== attempt

  useEffect(() => {
    if (!isAuthenticated) return undefined
    const controller = new AbortController()
    let active = true

    bookingService
      .listOwnBookings({ page, size: PAGE_SIZE, signal: controller.signal })
      .then((result) => {
        if (!active) return
        setBookings(result.content ?? [])
        setTotalPages(result.page?.totalPages ?? 1)
        setErrorMessage(null)
        setResolved({ page, attempt })
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
        setErrorMessage(error instanceof ApiError ? error.message : GENERIC_ERROR)
        setResolved({ page, attempt })
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [isAuthenticated, page, attempt])

  /* Replace the cancelled record with the backend's own response;
     a 409 (hold expired mid-flight, already cancelled, …) refreshes
     the whole page from the backend instead of guessing. */
  const handleCancel = async (bookingId) => {
    if (cancellingId) return
    setCancellingId(bookingId)
    setActionError(null)
    try {
      const updated = await bookingService.cancelBooking(bookingId)
      setBookings((current) =>
        current.map((booking) =>
          booking.bookingId === updated.bookingId ? updated : booking,
        ),
      )
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setActionError(error.message)
        setAttempt((current) => current + 1)
      } else {
        setActionError(
          error instanceof ApiError
            ? error.message
            : 'Could not cancel this booking. Please try again.',
        )
      }
    } finally {
      setCancellingId(null)
    }
  }

  const goToPage = (next) => {
    setPage(next)
    window.scrollTo(0, 0)
  }

  /* A payment attempt settles with the backend's own state; swap
     the refreshed record into the list. */
  const handleBookingUpdated = useCallback((updated) => {
    setBookings((current) =>
      current.map((booking) =>
        booking.bookingId === updated.bookingId ? updated : booking,
      ),
    )
  }, [])

  const retry = () => setAttempt((current) => current + 1)

  if (!isAuthenticated) {
    return (
      <main id="main" className="bookings fr-anim-fade-in">
        <div className="fr-container">
          <div className="bookings__state fr-surface-gradient">
            <h1 className="fr-subheading">Sign in to view your bookings</h1>
            <p className="fr-small bookings__state-detail">
              Your reserved seats, holds and confirmations live here once you
              are signed in.
            </p>
            <div className="bookings__state-actions">
              <Link
                to="/login"
                state={{ from: '/bookings' }}
                className="fr-btn fr-btn--primary"
              >
                Sign in
              </Link>
              <Link to="/events" className="fr-btn fr-btn--ghost">
                Browse events
              </Link>
            </div>
          </div>
        </div>
      </main>
    )
  }

  return renderBookingsView({
    isStale,
    errorMessage,
    bookings,
    totalPages,
    page,
    actionError,
    cancellingId,
    onCancel: handleCancel,
    onRetry: retry,
    onPage: goToPage,
    onBookingUpdated: handleBookingUpdated,
  })
}

function renderBookingsView({
  isStale,
  errorMessage,
  bookings,
  totalPages,
  page,
  actionError,
  cancellingId,
  onCancel,
  onRetry,
  onPage,
  onBookingUpdated,
}) {
  return (
    <main id="main" className="bookings fr-anim-fade-in">
      <div className="fr-container">
        <header className="bookings__header">
          <p className="bookings__eyebrow">FlashReserve · Your seats</p>
          <h1 className="fr-heading bookings__title">My bookings</h1>
          <p className="bookings__lead">
            Every seat you have held, confirmed, cancelled or let expire —
            exactly as the server reports it.
          </p>
        </header>

        {isStale && (
          <>
            <p className="bookings__sr" role="status">
              Loading bookings…
            </p>
            <div className="bookings__list" aria-hidden="true">
              <BookingsSkeleton />
              <BookingsSkeleton />
            </div>
          </>
        )}

        {!isStale && errorMessage && (
          <div className="bookings__state fr-surface-gradient">
            <h2 className="fr-subheading">We couldn&apos;t load your bookings</h2>
            <p className="fr-small bookings__state-detail">{errorMessage}</p>
            <Button onClick={onRetry}>Try again</Button>
          </div>
        )}

        {!isStale && !errorMessage && bookings.length === 0 && (
          <div className="bookings__state fr-surface-gradient">
            <h2 className="fr-subheading">No bookings yet.</h2>
            <p className="fr-small bookings__state-detail">
              Reserve a seat at one of our events and it will appear here
              while it is on hold.
            </p>
            <Link to="/events" className="fr-btn fr-btn--primary">
              Explore events
            </Link>
          </div>
        )}

        {!isStale && !errorMessage && bookings.length > 0 && (
          <>
            {actionError && <Alert>{actionError}</Alert>}
            <ul className="bookings__list">
              {bookings.map((booking) => (
                <li key={booking.bookingId}>
                  <PayableBookingCard
                    booking={booking}
                    onCancel={onCancel}
                    cancelling={cancellingId === booking.bookingId}
                    onBookingUpdated={onBookingUpdated}
                  />
                </li>
              ))}
            </ul>

            {totalPages > 1 && (
              <nav className="bookings__pager" aria-label="Bookings pages">
                <Button variant="ghost" disabled={page === 0} onClick={() => onPage(page - 1)}>
                  Previous
                </Button>
                <span className="fr-caption">
                  Page {page + 1} of {totalPages}
                </span>
                <Button
                  variant="ghost"
                  disabled={page >= totalPages - 1}
                  onClick={() => onPage(page + 1)}
                >
                  Next
                </Button>
              </nav>
            )}
          </>
        )}
      </div>
    </main>
  )
}

/* Calm skeleton mirroring the list layout while data loads. */
function BookingsSkeleton() {
  return (
    <div className="bookings__skeleton fr-surface">
      <span className="bookings__line bookings__line--chip" />
      <span className="bookings__line bookings__line--title" />
      <span className="bookings__line bookings__line--meta" />
      <span className="bookings__line bookings__line--meta bookings__line--short" />
    </div>
  )
}
