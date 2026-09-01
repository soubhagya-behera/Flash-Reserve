import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import BookingCard from '../components/bookings/BookingCard.jsx'
import Alert from '../components/ui/Alert.jsx'
import Button from '../components/ui/Button.jsx'
import { useAuth } from '../auth/authContext.js'
import { ApiError } from '../services/apiClient.js'
import * as bookingService from '../services/bookingService.js'
import './bookings.css'

/**
 * Full record of one of the caller's bookings, fetched from
 * GET /api/bookings/{bookingId}. Unknown and foreign bookings are
 * indistinguishable on the backend (both 404), so the not-found
 * state covers both. This page owns the loading and cancel
 * action; BookingCard stays purely presentational.
 */
export default function BookingDetailPage() {
  const { bookingId } = useParams()
  const { isAuthenticated } = useAuth()
  const [booking, setBooking] = useState(null)
  const [status, setStatus] = useState('loading') // loading | ready | notFound | error
  const [errorMessage, setErrorMessage] = useState(null)
  const [actionError, setActionError] = useState(null)
  const [cancelling, setCancelling] = useState(false)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (!isAuthenticated) return undefined
    const controller = new AbortController()
    let active = true

    bookingService
      .getBooking(bookingId, { signal: controller.signal })
      .then((result) => {
        if (!active) return
        setBooking(result)
        setStatus('ready')
        setErrorMessage(null)
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
        if (error instanceof ApiError && error.status === 404) {
          setStatus('notFound')
        } else {
          setErrorMessage(
            error instanceof ApiError
              ? error.message
              : 'Something went wrong while loading this booking. Please try again.',
          )
          setStatus('error')
        }
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [isAuthenticated, bookingId, attempt])

  /* The backend's cancel response is the new truth for this record. */
  const handleCancel = async () => {
    if (cancelling) return
    setCancelling(true)
    setActionError(null)
    try {
      setBooking(await bookingService.cancelBooking(bookingId))
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
      setCancelling(false)
    }
  }

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
                state={{ from: `/bookings/${bookingId}` }}
                className="fr-btn fr-btn--primary"
              >
                Sign in
              </Link>
            </div>
          </div>
        </div>
      </main>
    )
  }

  return (
    <main id="main" className="bookings fr-anim-fade-in">
      <div className="fr-container">
        <div className="bookings__detail">
          <Link to="/bookings" className="bookings__back">
            <span aria-hidden="true">←</span> My bookings
          </Link>

          {status === 'loading' && (
            <div className="bookings__skeleton fr-surface" aria-hidden="true">
              <span className="bookings__line bookings__line--chip" />
              <span className="bookings__line bookings__line--title" />
              <span className="bookings__line bookings__line--meta" />
              <span className="bookings__line bookings__line--meta bookings__line--short" />
            </div>
          )}

          {status === 'notFound' && (
            <div className="bookings__state fr-surface-gradient">
              <h1 className="fr-subheading">Booking not found</h1>
              <p className="fr-small bookings__state-detail">
                This booking may have been removed, or it belongs to a
                different account.
              </p>
              <Link to="/bookings" className="fr-btn fr-btn--primary">
                Back to my bookings
              </Link>
            </div>
          )}

          {status === 'error' && (
            <div className="bookings__state fr-surface-gradient">
              <h1 className="fr-subheading">We couldn&apos;t load this booking</h1>
              <p className="fr-small bookings__state-detail">{errorMessage}</p>
              <Button onClick={() => setAttempt((current) => current + 1)}>
                Try again
              </Button>
            </div>
          )}

          {status === 'ready' && (
            <>
              {actionError && <Alert>{actionError}</Alert>}
              <BookingCard
                booking={booking}
                detailed
                onCancel={handleCancel}
                cancelling={cancelling}
              />
            </>
          )}
        </div>
      </div>
    </main>
  )
}
