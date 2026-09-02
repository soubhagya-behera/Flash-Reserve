import { Link } from 'react-router-dom'
import StatusBadge from './StatusBadge.jsx'
import { formatEventDate, formatEventTime, formatTicketPrice } from '../../utils/format.js'
import './admin-table.css'
import './admin-widgets.css'

/** "1 Jun 2027, 6:00 PM" style schedule cell from a real instant. */
function formatWhen(instant) {
  const date = formatEventDate(instant)
  const time = formatEventTime(instant)
  if (!date) return '—'
  return time ? `${date}, ${time}` : date
}

/* Actions are driven by the real backend lifecycle contract:
   publish leaves DRAFT only, cancel leaves DRAFT/PUBLISHED only.
   Edit is a field replacement the backend permits in every
   status, so it is always offered. There is no unpublish and no
   COMPLETED transition — those actions are never rendered. */
function rowActions(event, onRequestAction) {
  return (
    <>
      <Link className="admin-table__action" to={`/admin/events/${event.id}/edit`}>
        Edit
      </Link>
      {event.status === 'DRAFT' && (
        <button
          type="button"
          className="admin-table__action admin-table__action--primary"
          onClick={() => onRequestAction('publish', event)}
        >
          Publish
        </button>
      )}
      {(event.status === 'DRAFT' || event.status === 'PUBLISHED') && (
        <button
          type="button"
          className="admin-table__action admin-table__action--danger"
          onClick={() => onRequestAction('cancel', event)}
        >
          Cancel
        </button>
      )}
    </>
  )
}

const CELL = 'admin-table__cell'

/**
 * The admin event list. A real table on desktop; the same markup
 * becomes stacked cards on small screens via CSS (data-label
 * attributes keep every value labelled). Purely presentational —
 * data and mutations belong to the page.
 */
export default function AdminEventTable({ events, onRequestAction }) {
  return (
    <div className="admin-table-wrap fr-surface">
      <table className="admin-table">
        <caption className="admin-table__caption sr-only">
          All events with venue, schedule, pricing, status and available actions
        </caption>
        <thead>
          <tr>
            <th scope="col">Event</th>
            <th scope="col">Venue</th>
            <th scope="col">Date &amp; time</th>
            <th scope="col">Price</th>
            <th scope="col">Seats</th>
            <th scope="col">Status</th>
            <th scope="col">Created</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          {events.map((event) => (
            <tr key={event.id}>
              <th scope="row" className={CELL} data-label="Event">
                <Link className="admin-table__name" to={`/admin/events/${event.id}`}>
                  {event.name}
                </Link>
              </th>
              <td className={CELL} data-label="Venue">{event.venue}</td>
              <td className={CELL} data-label="Date & time">
                {formatWhen(event.eventDate)}
              </td>
              <td className={CELL} data-label="Price">
                {formatTicketPrice(event.ticketPrice) ?? '—'}
              </td>
              <td className={CELL} data-label="Seats">{event.totalSeats}</td>
              <td className={CELL} data-label="Status">
                <StatusBadge status={event.status} />
              </td>
              <td className={CELL} data-label="Created">
                {formatEventDate(event.createdAt)}
              </td>
              <td className={CELL} data-label="Actions">
                <div className="admin-table__actions">
                  {rowActions(event, onRequestAction)}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}