import { Link } from 'react-router-dom'
import { formatEventDate, formatEventTime, formatTicketPrice } from '../../utils/format.js'
import './event-card.css'

/* Backend EventStatus values; public browsing only ever sees
   PUBLISHED, but the mapping stays complete for safety. */
const STATUS_LABELS = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  CANCELLED: 'Cancelled',
  COMPLETED: 'Completed',
}

/**
 * Presentation-only event card. Receives a backend event and
 * renders it; it never fetches and holds no state.
 */
export default function EventCard({ event }) {
  const price = formatTicketPrice(event.ticketPrice)
  const statusLabel = STATUS_LABELS[event.status] ?? event.status

  return (
    <article className="event-card fr-surface">
      <div className="event-card__top">
        <span className="event-card__date">{formatEventDate(event.eventDate)}</span>
        <span className="event-card__status">{statusLabel}</span>
      </div>

      <h3 className="event-card__name">
        <Link to={`/events/${event.id}`} className="event-card__name-link">
          {event.name}
        </Link>
      </h3>

      <p className="event-card__venue">{event.venue}</p>

      <div className="event-card__foot">
        <span className="event-card__time">{formatEventTime(event.eventDate)}</span>
        <span className="event-card__price">{price ?? 'Price to be announced'}</span>
      </div>

      <Link to={`/events/${event.id}`} className="event-card__cta">
        View event
        <span aria-hidden="true">→</span>
      </Link>
    </article>
  )
}
