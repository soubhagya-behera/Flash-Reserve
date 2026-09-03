import { useEffect, useState } from 'react'
import AdminBookingTable from '../../components/admin/AdminBookingTable.jsx'
import Button from '../../components/ui/Button.jsx'
import { ApiError } from '../../services/apiClient.js'
import * as bookingService from '../../services/bookingService.js'
import * as eventService from '../../services/eventService.js'
import './admin.css'

const PAGE_SIZE = 10
const GENERIC_ERROR = 'Something went wrong while loading bookings. Please try again.'

/* Filter chips. ALL means "no status query parameter" — the backend
   returns every status. REFUNDED is intentionally absent: the backend
   enum may hold it, but nothing currently produces that state, so it
   is not offered as an actionable filter. */
const FILTERS = [
  { value: 'ALL', label: 'All' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'CONFIRMED', label: 'Confirmed' },
  { value: 'EXPIRED', label: 'Expired' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

/**
 * Read-only admin catalog of every booking. Filter changes reset the
 * page and re-fetch from the backend; an in-flight request is aborted
 * so a slow answer can never overwrite a newer one.
 */
export default function AdminBookingsPage() {
  const [filter, setFilter] = useState('ALL')
  const [eventId, setEventId] = useState('')
  const [page, setPage] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const [resolved, setResolved] = useState({ filter: null, eventId: null, page: -1, attempt: -1 })
  const [bookings, setBookings] = useState([])
  const [totalPages, setTotalPages] = useState(1)
  const [totalElements, setTotalElements] = useState(0)
  const [errorMessage, setErrorMessage] = useState(null)
  const [events, setEvents] = useState([])

  const isStale = resolved.filter !== filter || resolved.eventId !== eventId
    || resolved.page !== page || resolved.attempt !== attempt

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    bookingService
      .listAdminBookings({
        status: filter === 'ALL' ? undefined : filter,
        eventId: eventId || undefined,
        page,
        size: PAGE_SIZE,
        signal: controller.signal,
      })
      .then((result) => {
        if (!active) return
        setBookings(result.content ?? [])
        setTotalPages(result.page?.totalPages ?? 1)
        setTotalElements(result.page?.totalElements ?? 0)
        setErrorMessage(null)
        setResolved({ filter, eventId, page, attempt })
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
        setErrorMessage(error instanceof ApiError ? error.message : GENERIC_ERROR)
        setResolved({ filter, eventId, page, attempt })
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [filter, eventId, page, attempt])

  // Filter options come from the existing admin event list — no new
  // API surface. First page only, so very large catalogs may not all
  // appear here; the eventId API filter itself remains fully usable.
  useEffect(() => {
    const controller = new AbortController()
    let active = true

    eventService
      .listAdminEvents({ page: 0, size: 100, signal: controller.signal })
      .then((result) => {
        if (active) setEvents(result.content ?? [])
      })
      .catch(() => {
        // The event selector is a convenience filter; if it cannot
        // load, the bookings list still works unfiltered.
        if (active) setEvents([])
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [])

  const chooseFilter = (next) => {
    setFilter(next)
    setPage(0)
    window.scrollTo(0, 0)
  }

  const chooseEvent = (next) => {
    setEventId(next)
    setPage(0)
    window.scrollTo(0, 0)
  }

  const goToPage = (next) => {
    setPage(next)
    window.scrollTo(0, 0)
  }

  return (
    <main id="main" className="admin fr-anim-fade-in">
      <div className="fr-container">
        <header className="admin__header">
          <div>
            <p className="admin__eyebrow">FlashReserve · Administration</p>
            <h1 className="fr-heading admin__title">Bookings</h1>
            <p className="admin__lead">
              Every booking across every status — who booked which seat, for
              which event, and how far the payment got. This view is read-only;
              booking lifecycles are driven by the customers themselves.
            </p>
          </div>
        </header>

        {errorMessage && (
          <div className="admin__state fr-surface-gradient">
            <h2 className="fr-subheading">We couldn&apos;t load bookings</h2>
            <p className="fr-small admin__state-detail">{errorMessage}</p>
            <Button onClick={() => setAttempt((current) => current + 1)}>Try again</Button>
          </div>
        )}

        {!isStale && !errorMessage && bookings.length === 0 && (
          <div className="admin__state fr-surface-gradient">
            <h2 className="fr-subheading">
              {filter === 'ALL' && !eventId
                ? 'No bookings exist yet.'
                : 'No bookings match these filters.'}
            </h2>
            <p className="fr-small admin__state-detail">
              {filter === 'ALL' && !eventId
                ? 'Bookings appear here the moment a customer holds a seat.'
                : 'Try a different status or event filter.'}
            </p>
          </div>
        )}

        {(!isStale || bookings.length > 0) && !errorMessage && (
          <>
            <div className="admin__toolbar">
              <div className="admin__filters" role="group" aria-label="Filter bookings by status">
                {FILTERS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    className={`admin__filter${filter === option.value ? ' admin__filter--active' : ''}`}
                    aria-pressed={filter === option.value}
                    onClick={() => chooseFilter(option.value)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>

              {events.length > 0 && (
                <div className="admin__event-filter">
                  <label htmlFor="admin-booking-event">Event</label>
                  <select
                    id="admin-booking-event"
                    className="admin__event-select"
                    value={eventId}
                    onChange={(change) => chooseEvent(change.target.value)}
                  >
                    <option value="">All events</option>
                    {events.map((event) => (
                      <option key={event.id} value={event.id}>{event.name}</option>
                    ))}
                  </select>
                </div>
              )}

              {!isStale && (
                <p className="fr-caption admin__count" role="status">
                  {totalElements} booking{totalElements === 1 ? '' : 's'}
                  {filter !== 'ALL' ? ` · ${filter.toLowerCase()}` : ''}
                </p>
              )}
            </div>

            {!isStale && <AdminBookingTable bookings={bookings} />}
            {isStale && bookings.length === 0 && (
              <div className="admin-detail-card fr-surface-elevated">
                <div className="admin-form__skeleton" aria-hidden="true">
                  {Array.from({ length: 4 }, (_, index) => (
                    <span key={index} className="admin-form__skeleton-line" />
                  ))}
                </div>
              </div>
            )}
            {isStale && bookings.length > 0 && (
              <p className="admin__sr" role="status">Loading bookings…</p>
            )}

            {!isStale && totalPages > 1 && (
              <nav className="admin__pager" aria-label="Booking pages">
                <Button
                  variant="ghost"
                  disabled={page === 0}
                  onClick={() => goToPage(page - 1)}
                >
                  Previous
                </Button>
                <span className="fr-caption">
                  Page {page + 1} of {totalPages}
                </span>
                <Button
                  variant="ghost"
                  disabled={page >= totalPages - 1}
                  onClick={() => goToPage(page + 1)}
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