import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import Button from '../components/ui/Button.jsx'
import SeatMap from '../components/events/SeatMap.jsx'
import { useAuth } from '../auth/authContext.js'
import { ApiError } from '../services/apiClient.js'
import * as eventService from '../services/eventService.js'
import { formatEventDate, formatEventTime, formatTicketPrice } from '../utils/format.js'
import './event-detail.css'

const STATUS_LABELS = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
}

/**
 * Public detail view of one published event with its real seat
 * map. Selection here is a local preview only — nothing is held
 * and no reservation is submitted; that arrives in a later commit.
 */
export default function EventDetailPage() {
  const { eventId } = useParams()
  const { isAuthenticated } = useAuth()
  const [event, setEvent] = useState(null)
  const [seats, setSeats] = useState([])
  const [status, setStatus] = useState('loading') // loading | ready | notFound | error
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedSeatIds, setSelectedSeatIds] = useState([])
  const [nonce, setNonce] = useState(0)
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
        setSelectedSeatIds([])
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

  const toggleSeat = (seatId) => {
    setSelectedSeatIds((current) =>
      current.includes(seatId)
        ? current.filter((id) => id !== seatId)
        : [...current, seatId],
    )
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
    selectedSeatIds,
    onToggleSeat: toggleSeat,
    isAuthenticated,
  })
}

function renderReadyView({ event, seats, selectedSeatIds, onToggleSeat, isAuthenticated }) {
  const price = formatTicketPrice(event.ticketPrice)
  const statusLabel = STATUS_LABELS[event.status] ?? event.status
  const availableCount = seats.filter((seat) => seat.status === 'AVAILABLE').length
  const selectedSeats = seats.filter((seat) => selectedSeatIds.includes(seat.id))

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
            selectedSeatIds={selectedSeatIds}
            onToggleSeat={onToggleSeat}
          />

          <div className="event-detail__selection">
            <div>
              <h3 className="event-detail__selection-title">Your selection</h3>
              {selectedSeats.length > 0 ? (
                <p className="event-detail__selection-seats">
                  {selectedSeats.map((seat) => seat.seatNumber).join(', ')}
                </p>
              ) : (
                <p className="event-detail__selection-empty fr-small">
                  No seats selected yet — pick the seats you want from the map.
                </p>
              )}
              <p className="event-detail__note fr-caption">
                Selecting a seat does not reserve it.{' '}
                {isAuthenticated ? 'You are signed in — ' : 'You will sign in when '}
                seat reservation arrives in the next update.
              </p>
            </div>

            <Button disabled>Continue to reservation</Button>
          </div>
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
