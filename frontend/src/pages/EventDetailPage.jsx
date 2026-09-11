import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import Button from '../components/ui/Button.jsx'
import { useAuth } from '../auth/authContext.js'
import { ApiError } from '../services/apiClient.js'
import * as eventService from '../services/eventService.js'
import * as reservationService from '../services/reservationService.js'
import { useLiveSeats } from '../hooks/useLiveSeats.js'
import EventDetailView, { EventDetailSkeleton } from '../components/events/EventDetailView.jsx'
import './event-detail.css'

export default function EventDetailPage() {
  const { eventId } = useParams()
  const navigate = useNavigate()
  const { isAuthenticated, logout } = useAuth()
  const [event, setEvent] = useState(null)
  const [seats, setSeats] = useState([])
  const [status, setStatus] = useState('loading')
  const [errorMessage, setErrorMessage] = useState('')
  const [selectedSeatId, setSelectedSeatId] = useState(null)
  const [reservation, setReservation] = useState(null)
  const [reserving, setReserving] = useState(false)
  const [reservationError, setReservationError] = useState(null)
  const [nonce, setNonce] = useState(0)
  const [seatsNonce, setSeatsNonce] = useState(0)
  const [resolved, setResolved] = useState(null)

  const isStale = resolved !== `${eventId}:${nonce}`

  const { seedFromSnapshot } = useLiveSeats({
    eventId,
    enabled: status === 'ready',
    seats,
    setSeats,
    setSelectedSeatId,
    setReservationError,
    onUnknownSeat: () => setSeatsNonce((c) => c + 1),
  })

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
        seedFromSnapshot(seatList ?? [])
        setStatus('ready')
        setResolved(resolvedKey)
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
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
  }, [eventId, nonce, seedFromSnapshot])

  const isInitialSeatsRun = useRef(true)
  useEffect(() => {
    if (isInitialSeatsRun.current) {
      isInitialSeatsRun.current = false
      return
    }
    const controller = new AbortController()
    eventService
      .listEventSeats(eventId, { signal: controller.signal })
      .then((seatList) => {
        // reseed versions from snapshot — makes SSE ordering deterministic
        seedFromSnapshot(seatList ?? [])
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
      })
    return () => controller.abort()
  }, [eventId, seatsNonce, seedFromSnapshot])

  const toggleSeat = (seatId) => {
    setSelectedSeatId((current) => (current === seatId ? null : seatId))
  }

  const handleContinue = async () => {
    if (reserving || !selectedSeatId) return
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
      setSeatsNonce((c) => c + 1)
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setReservationError(error.message ?? 'That seat is no longer available. Someone else may have reserved it.')
        setSelectedSeatId(null)
        setSeatsNonce((c) => c + 1)
      } else if (error instanceof ApiError && error.status === 401) {
        logout()
        setReservationError('Your session has expired. Please sign in to reserve your seat.')
        setSelectedSeatId(null)
      } else {
        setReservationError(error instanceof ApiError ? error.message : 'Could not reserve your seat. Please try again.')
      }
    } finally {
      setReserving(false)
    }
  }

  const retry = () => setNonce((c) => c + 1)

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
            <h1 className="fr-subheading">{notFound ? 'Event not found' : "We couldn't load this event"}</h1>
            <p className="fr-small event-detail__state-detail">
              {notFound ? 'This event may have been removed, or the link is incorrect.' : errorMessage}
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

  return (
    <EventDetailView
      event={event}
      seats={seats}
      selectedSeatId={selectedSeatId}
      onToggleSeat={toggleSeat}
      isAuthenticated={isAuthenticated}
      reservation={reservation}
      reserving={reserving}
      reservationError={reservationError}
      onContinue={handleContinue}
      onReservationDismiss={() => setReservation(null)}
      onReservationRefresh={() => {
        setReservation(null)
        setSeatsNonce((c) => c + 1)
      }}
    />
  )
}
