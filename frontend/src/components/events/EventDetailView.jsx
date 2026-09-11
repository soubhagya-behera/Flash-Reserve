import { Link } from 'react-router-dom'
import Button from '../ui/Button.jsx'
import SeatMap from './SeatMap.jsx'
import ReservationPanel from './ReservationPanel.jsx'
import Alert from '../ui/Alert.jsx'
import { formatEventDate, formatEventTime, formatTicketPrice } from '../../utils/format.js'

const STATUS_LABELS = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
}

export default function EventDetailView({
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
            {event.venue} · {formatEventDate(event.eventDate)} · {formatEventTime(event.eventDate)}
          </p>

          <div className="event-detail__chips">
            {price && <span className="event-detail__chip event-detail__chip--price">{price}</span>}
            <span className="event-detail__chip event-detail__chip--status">{statusLabel}</span>
            <span className="event-detail__chip">
              {event.totalSeats} seats · {availableCount} available
            </span>
          </div>

          {event.description && <p className="event-detail__description">{event.description}</p>}
        </header>

        <section className="event-detail__seats fr-surface" aria-labelledby="seat-selection-heading">
          <div className="event-detail__seats-head">
            <h2 id="seat-selection-heading" className="fr-subheading">
              Choose your seats
            </h2>
            <p className="fr-caption">
              {availableCount} of {seats.length} seats available
            </p>
          </div>

          <SeatMap seats={seats} selectedSeatIds={selectedSeatId ? [selectedSeatId] : []} onToggleSeat={onToggleSeat} />

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
                  <p className="event-detail__selection-seats">{selectedSeat.seatNumber}</p>
                ) : (
                  <p className="event-detail__selection-empty fr-small">
                    No seat selected yet — pick the seat you want from the map.
                  </p>
                )}
                <p className="event-detail__note fr-caption">
                  Selecting a seat does not reserve it — the hold is only created once the server confirms your
                  reservation. {!isAuthenticated && 'You will be asked to sign in first.'}
                </p>
              </div>

              <Button onClick={onContinue} disabled={!selectedSeat || reserving} aria-busy={reserving}>
                {reserving ? 'Reserving…' : isAuthenticated ? 'Continue to reservation' : 'Sign in to reserve'}
              </Button>
            </div>
          )}
        </section>
      </div>
    </main>
  )
}

export function EventDetailSkeleton() {
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
