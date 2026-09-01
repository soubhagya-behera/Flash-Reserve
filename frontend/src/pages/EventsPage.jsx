import { useEffect, useState } from 'react'
import Button from '../components/ui/Button.jsx'
import EventCard from '../components/events/EventCard.jsx'
import EventCardSkeleton from '../components/events/EventCardSkeleton.jsx'
import { ApiError } from '../services/apiClient.js'
import * as eventService from '../services/eventService.js'
import './events.css'

const PAGE_SIZE = 24
const GENERIC_ERROR = 'Something went wrong while loading events. Please try again.'

/**
 * Public catalog of published events, fetched from the real
 * backend. This page owns all data loading; EventCard stays
 * purely presentational. "Loading" is derived by comparing the
 * requested page/attempt with the last resolved one, so the
 * effect never sets state synchronously.
 */
export default function EventsPage() {
  const [page, setPage] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const [resolved, setResolved] = useState({ page: -1, attempt: -1 })
  const [events, setEvents] = useState([])
  const [totalPages, setTotalPages] = useState(1)
  const [errorMessage, setErrorMessage] = useState(null)

  const isStale = resolved.page !== page || resolved.attempt !== attempt

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    eventService
      .listPublishedEvents({ page, size: PAGE_SIZE, signal: controller.signal })
      .then((result) => {
        if (!active) return
        setEvents(result.content ?? [])
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
  }, [page, attempt])

  const goToPage = (next) => {
    setPage(next)
    window.scrollTo(0, 0)
  }

  const retry = () => setAttempt((current) => current + 1)


  return (
    <main id="main" className="events fr-anim-fade-in">
      <div className="fr-container">
        <header className="events__header">
          <p className="events__eyebrow">FlashReserve · What&apos;s on</p>
          <h1 className="fr-heading events__title">Explore events</h1>
          <p className="events__lead">
            Every published event in one calm catalog. Find a date, find your
            venue, and step closer to the seat you want.
          </p>
        </header>

        {isStale && (
          <>
            <p className="events__sr" role="status">
              Loading events…
            </p>
            <div className="events__grid" aria-hidden="true">
              {Array.from({ length: 6 }, (_, index) => (
                <EventCardSkeleton key={index} />
              ))}
            </div>
          </>
        )}

        {!isStale && errorMessage && (
          <div className="events__state fr-surface-gradient">
            <h2 className="fr-subheading">We couldn&apos;t load events</h2>
            <p className="fr-small events__state-detail">{errorMessage}</p>
            <Button onClick={retry}>Try again</Button>
          </div>
        )}

        {!isStale && !errorMessage && events.length === 0 && (
          <div className="events__state fr-surface-gradient">
            <h2 className="fr-subheading">No events are open right now.</h2>
            <p className="fr-small events__state-detail">
              New events appear here the moment organizers publish them.
              Check back soon.
            </p>
          </div>
        )}

        {!isStale && !errorMessage && events.length > 0 && (
          <>
            <ul className="events__grid">
              {events.map((event) => (
                <li key={event.id}>
                  <EventCard event={event} />
                </li>
              ))}
            </ul>

            {totalPages > 1 && (
              <nav className="events__pager" aria-label="Events pages">
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
