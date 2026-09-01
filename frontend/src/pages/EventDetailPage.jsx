import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import Button from '../components/ui/Button.jsx'
import SeatMap from '../components/events/SeatMap.jsx'
import ReservationPanel from '../components/events/ReservationPanel.jsx'
import Alert from '../components/ui/Alert.jsx'
import { useAuth } from '../auth/authContext.js'
import { ApiError } from '../services/apiClient.js'
import * as eventService from '../services/eventService.js'
import * as reservationService from '../services/reservationService.js'
import { formatEventDate, formatEventTime, formatTicketPrice } from '../utils/format.js'
import './event-detail.css'

const STATUS_LABELS = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
}

/**
 * Public detail view of one published event with its real seat map
 * and the real reservation flow. Selecting a seat is local only;
 * the seat is actually held (PENDING booking, HELD seat) when the
 * user continues and the backend accepts the reservation.
 */
export default function EventDetailPage() {
  const { eventId } = useParams()
  const navigate = useNavigate()
  const { isAuthenticated, logout } = useAuth()
  const [event, setEvent] = useState(null)
  const [seats, setSeats] = useState([])
  const [status, setStatus] = useState('loading') // loading | ready | notFound | error
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedSeatId, setSelectedSeatId] = useState(null)
  const [reservation, setReservation] = useState(null)
  const [reserving, setReserving] = useState(false)
  const [reservationError, setReservationError] = useState(null)
  const [nonce, setNonce] = useState(0)
  const [seatsNonce, setSeatsNonce] = useState(0)
  const [resolved, setResolved] = useState(null)

  /* "Loading" is derived by comparing the requested event/attempt
     with the last resolved one, so the effect never sets state
     synchronously during render cycles. */
  const isStale = resolved !== `${eventId}:${nonce}`

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    const resolvedKey = `${eventId}:${nonce}`

    Promise.all([
      eventService.getPublishedEvent(eventId, { signal: controller.signal }),
      eventService.listEventSeats(eventId, { signal: controller.signal }),
    ])
      .then(([eventData, seatList]) => {
        if (!active) return
        setSelectedSeatId(null)
        setReservation(null)
        setReservationError(null)
        setEvent(eventData)
        setSeats(seatList ?? [])
        setStatus('ready')
        setResolved(resolvedKey)
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
        // A malformed UUID (400) and an unknown/unpublished event
        // (404) both mean: this event cannot be browsed.
        if (error instanceof ApiError && (error.status === 404 || error.status === 400)) {
          setStatus('notFound')
        } else {
          setErrorMessage(
            error instanceof ApiError
              ? error.message
              : 'Something went wrong while loading this event. Please try again.',
          )
          setStatus('error')
        }
        setResolved(resolvedKey)
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [eventId, nonce])

  /* Availability-only refresh (after a reservation result or a
     conflict). Skips the initial mount: the load effect above has
     already fetched the seats. Errors stay silent here — the map
     simply keeps its last known state and the next action retries. */
  const isInitialSeatsRun = useRef(true)
  useEffect(() => {
    if (isInitialSeatsRun.current) {
      isInitialSeatsRun.current = false
      return
    }
    const controller = new AbortController()
    eventService
      .listEventSeats(eventId, { signal: controller.signal })
      .then((seatList) => setSeats(seatList ?? []))
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        // Best-effort refresh; stale state is corrected on the next action.
      })
    return () => controller.abort()
  }, [eventId, seatsNonce])

  /* One seat per reservation — the backend endpoint holds exactly
     one seat per call, so selection is single-seat: picking another
     seat replaces the current choice. */
  const toggleSeat = (seatId) => {
    setSelectedSeatId((current) => (current === seatId ? null : seatId))
  }

  const handleContinue = async () => {
    if (reserving || !selectedSeatId) return

    // Never send the reservation request unauthenticated; sign in
    // first and come straight back to this event afterwards.
    if (!isAuthenticated) {
      navigate('/login', { state: { from: `/events/${eventId}` } })
      return
    }

    setReserving(true)
    setReservationError(null)
    try {
      const result = await reservationService.reserveSeat(eventId, selectedSeatId)
      setReservation(result)
      setSelectedSeatId(null)
      // Re-fetch real availability: the backend now reports this seat HELD.
      setSeatsNonce((current) => current + 1)
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        // Someone else reserved the seat first. Clear the selection and
        // refresh real availability — never fake the seat's new state.
        setReservationError(
          error.message ??
            'That seat is no longer available. Someone else may have reserved it.',
        )
        setSelectedSeatId(null)
        setSeatsNonce((current) => current + 1)
      } else if (error instanceof ApiError && error.status === 401) {
        // A session can also expire while the page stays open, after
        // the restored auth state was checked. Clear the dead session
        // through the existing auth mechanism: the UI returns to its
        // normal unauthenticated path (Continue → sign in → return).
        logout()
        setReservationError(
          'Your session has expired. Please sign in to reserve your seat.',
        )
        setSelectedSeatId(null)
      } else {
        setReservationError(
          error instanceof ApiError
            ? error.message
            : 'Could not reserve your seat. Please try again.',
        )
      }
    } finally {
      setReserving(false)
    }
  }

  const retry = () => setNonce((current) => current + 1)

  if (status === 'loading' || isStale) {
    return (
      <main id="main" className="event-detail fr-anim-fade-in">
        <div className="fr-container event-detail__container">
          <EventDetailSkeleton />
        </div>
      </main>
    )
  }

  if (status === 'notFound' || status === 'error') {
    const notFound = status === 'notFound'
    return (
      <main id="main" className="event-detail fr-anim-fade-in">
        <div className="fr-container">
          <div className="event-detail__state fr-surface-gradient">
            <h1 className="fr-subheading">
              {notFound ? 'Event not found' : "We couldn't load this event"}
            </h1>
            <p className="fr-small event-detail__state-detail">
              {notFound
                ? 'This event may have been removed, or the link is incorrect.'
                : errorMessage}
            </p>
            <div className="event-detail__state-actions">
              {!notFound && <Button onClick={retry}>Try again</Button>}
              <Link to="/events" className="fr-btn fr-btn--primary">
                Browse all events
              </Link>
            </div>
          </div>
        </div>
      </main>
    )
  }

  return renderReadyView({
    event,
    seats,
    selectedSeatId,
    onToggleSeat: toggleSeat,
    isAuthenticated,
    reservation,
    reserving,
    reservationError,
    onContinue: handleContinue,
    onReservationDismiss: () => setReservation(null),
    onReservationRefresh: () => {
      setReservation(null)
      setSeatsNonce((current) => current + 1)
    },
  })
}

function renderReadyView({
  event,
  seats,
  selectedSeatId,
  onToggleSeat,
  isAuthenticated,
  reservation,
  reserving,
  reservationError,
  onContinue,
  onReservationDismiss,
  onReservationRefresh,
}) {
  const price = formatTicketPrice(event.ticketPrice)
  const statusLabel = STATUS_LABELS[event.status] ?? event.status
  const availableCount = seats.filter((seat) => seat.status === 'AVAILABLE').length
  const selectedSeat = seats.find((seat) => seat.id === selectedSeatId) ?? null

  return (
    <main id="main" className="event-detail fr-anim-fade-in">
      <div className="fr-container event-detail__container">
        <Link to="/events" className="event-detail__back">
          <span aria-hidden="true">←</span> All events
        </Link>

        <header className="event-detail__header fr-surface-elevated">
          <p className="event-detail__eyebrow">Event</p>
          <h1 className="fr-heading">{event.name}</h1>
          <p className="event-detail__meta">
            {event.venue} · {formatEventDate(event.eventDate)} ·{' '}
            {formatEventTime(event.eventDate)}
          </p>

          <div className="event-detail__chips">
            {price && (
              <span className="event-detail__chip event-detail__chip--price">{price}</span>
            )}
            <span className="event-detail__chip event-detail__chip--status">{statusLabel}</span>
            <span className="event-detail__chip">
              {event.totalSeats} seats · {availableCount} available
            </span>
          </div>

          {event.description && (
            <p className="event-detail__description">{event.description}</p>
          )}
        </header>

        <section
          className="event-detail__seats fr-surface"
          aria-labelledby="seat-selection-heading"
        >
          <div className="event-detail__seats-head">
            <h2 id="seat-selection-heading" className="fr-subheading">
              Choose your seats
            </h2>
            <p className="fr-caption">
              {availableCount} of {seats.length} seats available
            </p>
          </div>

          <SeatMap
            seats={seats}
            selectedSeatIds={selectedSeatId ? [selectedSeatId] : []}
            onToggleSeat={onToggleSeat}
          />

          {reservationError ? <Alert>{reservationError}</Alert> : null}

          {reservation ? (
            <ReservationPanel
              reservation={reservation}
              eventName={event.name}
              onRefreshAvailability={onReservationRefresh}
              onReserveAnotherSeat={onReservationDismiss}
            />
          ) : (
            <div className="event-detail__selection">
              <div>
                <h3 className="event-detail__selection-title">Your selection</h3>
                {selectedSeat ? (
                  <p className="event-detail__selection-seats">
                    {selectedSeat.seatNumber}
                  </p>
                ) : (
                  <p className="event-detail__selection-empty fr-small">
                    No seat selected yet — pick the seat you want from the map.
                  </p>
                )}
                <p className="event-detail__note fr-caption">
                  Selecting a seat does not reserve it — the hold is only
                  created once the server confirms your reservation.{' '}
                  {!isAuthenticated && 'You will be asked to sign in first.'}
                </p>
              </div>

              <Button
                onClick={onContinue}
                disabled={!selectedSeat || reserving}
                aria-busy={reserving}
              >
                {reserving
                  ? 'Reserving…'
                  : isAuthenticated
                    ? 'Continue to reservation'
                    : 'Sign in to reserve'}
              </Button>
            </div>
          )}
        </section>
      </div>
    </main>
  )
}

/* Calm skeleton mirroring the ready layout while data loads. */
function EventDetailSkeleton() {
  return (
    <div className="event-detail__skeleton" aria-hidden="true">
      <span className="event-detail__line event-detail__line--back" />
      <div className="event-detail__header fr-surface-elevated">
        <span className="event-detail__line event-detail__line--eyebrow" />
        <span className="event-detail__line event-detail__line--title" />
        <span className="event-detail__line event-detail__line--meta" />
        <span className="event-detail__line event-detail__line--chip" />
      </div>
      <div className="event-detail__seats fr-surface">
        <span className="event-detail__line event-detail__line--title event-detail__line--short" />
        <span className="event-detail__line event-detail__line--block" />
      </div>
    </div>
  )
}
