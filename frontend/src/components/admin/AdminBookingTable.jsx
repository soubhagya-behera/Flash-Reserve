import { Link } from 'react-router-dom'
import StatusBadge from './StatusBadge.jsx'
import { formatEventDate, formatTicketPrice } from '../../utils/format.js'
import './admin-table.css'
import './admin-widgets.css'

/**
 * Short human-friendly booking label: the leading chunk of the
 * backend UUID. The full id lives in the detail page and in the
 * link target, so nothing is lost — this only keeps the table
 * scannable.
 */
function bookingLabel(bookingId) {
  return `#${bookingId.slice(0, 8)}`
}

/**
 * Payment summary cell. A booking legitimately has no payment row
 * until checkout is initiated, so absence is a normal state, never
 * an error. Only the fields the backend actually returns are shown.
 */
function paymentCell(payment) {
  if (!payment) {
    return <span className="admin-table__none">No payment initiated</span>
  }
  return (
    <>
      {payment.paymentStatus}
      {payment.razorpayPaymentId && (
        <span className="admin-table__sub">{payment.razorpayPaymentId}</span>
      )}
    </>
  )
}

const CELL = 'admin-table__cell'

/**
 * The admin booking list. Same anatomy as AdminEventTable: a real
 * table on desktop that becomes stacked, labelled cards on small
 * screens via CSS (data-label attributes keep every value
 * labelled). Purely presentational and read-only — the backend
 * exposes no admin booking mutations, so none are offered.
 */
export default function AdminBookingTable({ bookings }) {
  return (
    <div className="admin-table-wrap fr-surface">
      <table className="admin-table">
        <caption className="admin-table__caption sr-only">
          All bookings with event, seat, booker, status, payment and creation date
        </caption>
        <thead>
          <tr>
            <th scope="col">Booking</th>
            <th scope="col">Event</th>
            <th scope="col">Seat</th>
            <th scope="col">Booker</th>
            <th scope="col">Status</th>
            <th scope="col">Amount</th>
            <th scope="col">Payment</th>
            <th scope="col">Created</th>
          </tr>
        </thead>
        <tbody>
          {bookings.map((booking) => (
            <tr key={booking.bookingId}>
              <th scope="row" className={CELL} data-label="Booking">
                <Link
                  className="admin-table__name admin-table__id"
                  to={`/admin/bookings/${booking.bookingId}`}
                  title={booking.bookingId}
                >
                  {bookingLabel(booking.bookingId)}
                </Link>
              </th>
              <td className={CELL} data-label="Event">{booking.eventName}</td>
              <td className={CELL} data-label="Seat">{booking.seatNumber}</td>
              <td className={CELL} data-label="Booker">
                {booking.bookerName}
                <span className="admin-table__sub">{booking.bookerEmail}</span>
              </td>
              <td className={CELL} data-label="Status">
                <StatusBadge status={booking.status} />
              </td>
              <td className={CELL} data-label="Amount">
                {formatTicketPrice(booking.payment?.amount) ?? '—'}
              </td>
              <td className={CELL} data-label="Payment">
                {paymentCell(booking.payment)}
              </td>
              <td className={CELL} data-label="Created">
                {formatEventDate(booking.createdAt)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}