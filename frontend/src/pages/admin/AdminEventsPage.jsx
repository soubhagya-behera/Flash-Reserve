import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import AdminEventTable from '../../components/admin/AdminEventTable.jsx'
import ConfirmDialog from '../../components/admin/ConfirmDialog.jsx'
import useEventAction from '../../components/admin/useEventAction.js'
import Alert from '../../components/ui/Alert.jsx'
import Button from '../../components/ui/Button.jsx'
import { ApiError } from '../../services/apiClient.js'
import * as eventService from '../../services/eventService.js'
import './admin.css'

const PAGE_SIZE = 10
const GENERIC_ERROR = 'Something went wrong while loading events. Please try again.'

/* Filter chips. ALL means "no status query parameter" — the backend
   returns every status; nothing is counted or derived locally. */
const FILTERS = [
  { value: 'ALL', label: 'All' },
  { value: 'DRAFT', label: 'Draft' },
  { value: 'PUBLISHED', label: 'Published' },
  { value: 'CANCELLED', label: 'Cancelled' },
  { value: 'COMPLETED', label: 'Completed' },
]

export default function AdminEventsPage() {
  const location = useLocation()
  const [filter, setFilter] = useState('ALL')
  const [page, setPage] = useState(0)
  const [attempt, setAttempt] = useState(0)
  const [resolved, setResolved] = useState({ filter: null, page: -1, attempt: -1 })
  const [events, setEvents] = useState([])
  const [totalPages, setTotalPages] = useState(1)
  const [totalElements, setTotalElements] = useState(0)
  const [errorMessage, setErrorMessage] = useState(null)

  const isStale =
    resolved.filter !== filter || resolved.page !== page || resolved.attempt !== attempt

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    eventService
      .listAdminEvents({
        status: filter === 'ALL' ? undefined : filter,
        page,
        size: PAGE_SIZE,
        signal: controller.signal,
      })
      .then((result) => {
        if (!active) return
        setEvents(result.content ?? [])
        setTotalPages(result.page?.totalPages ?? 1)
        setTotalElements(result.page?.totalElements ?? 0)
        setErrorMessage(null)
        setResolved({ filter, page, attempt })
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
        setErrorMessage(error instanceof ApiError ? error.message : GENERIC_ERROR)
        setResolved({ filter, page, attempt })
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [filter, page, attempt])

  /* Publish/cancel run against the real endpoints; a successful
     mutation bumps the attempt so the list is re-fetched from the
     backend — the status shown is always the server's truth. */
  const executor = useCallback(async (action, event) => {
    if (action === 'publish') {
      await eventService.publishAdminEvent(event.id)
    } else {
      await eventService.cancelAdminEvent(event.id)
    }
  }, [])

  const lifecycle = useEventAction(executor)

  const handleActionConfirmed = async () => {
    await lifecycle.confirm()
    setAttempt((current) => current + 1)
  }

  const chooseFilter = (next) => {
    setFilter(next)
    setPage(0)
    window.scrollTo(0, 0)
  }

  const goToPage = (next) => {
    setPage(next)
    window.scrollTo(0, 0)
  }

  const flash = location.state?.flash ?? null

  return (
    <main id="main" className="admin fr-anim-fade-in">
      <div className="fr-container">
        <header className="admin__header">
          <div>
            <p className="admin__eyebrow">FlashReserve · Administration</p>
            <h1 className="fr-heading admin__title">Manage events</h1>
            <p className="admin__lead">
              Every event across every status — drafts, published listings,
              cancellations and completed shows. Publishing and cancelling
              act immediately on the live backend.
            </p>
          </div>
          <Link to="/admin/events/new" className="fr-btn fr-btn--primary admin__create">
            Create event
          </Link>
        </header>

        {flash && (
          <div role="status">
            <Alert tone="info">{flash}</Alert>
          </div>
        )}

        {lifecycle.successMessage && (
          <div role="status">
            <Alert tone="info">{lifecycle.successMessage}</Alert>
          </div>
        )}

        {lifecycle.actionError && <Alert>{lifecycle.actionError}</Alert>}

        {!isStale && errorMessage && (
          <div className="admin__state fr-surface-gradient">
            <h2 className="fr-subheading">We couldn&apos;t load events</h2>
            <p className="fr-small admin__state-detail">{errorMessage}</p>
            <Button onClick={() => setAttempt((current) => current + 1)}>Try again</Button>
          </div>
        )}

        {!isStale && !errorMessage && events.length === 0 && (
          <div className="admin__state fr-surface-gradient">
            <h2 className="fr-subheading">
              {filter === 'ALL'
                ? 'No events exist yet.'
                : `No ${FILTERS.find((f) => f.value === filter)?.label.toLowerCase()} events.`}
            </h2>
            <p className="fr-small admin__state-detail">
              {filter === 'ALL'
                ? 'Create the first event and it will appear here as a draft.'
                : 'Try a different status filter, or create a new event.'}
            </p>
            <Link to="/admin/events/new" className="fr-btn fr-btn--primary">
              Create event
            </Link>
          </div>
        )}

        {(!isStale || events.length > 0) && !errorMessage && (
          <>
            <div className="admin__toolbar">
              <div className="admin__filters" role="group" aria-label="Filter events by status">
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
              {!isStale && (
                <p className="fr-caption admin__count" role="status">
                  {totalElements} event{totalElements === 1 ? '' : 's'}
                  {filter !== 'ALL' ? ` · ${filter.toLowerCase()}` : ''}
                </p>
              )}
            </div>

            {!isStale && (
              <AdminEventTable events={events} onRequestAction={lifecycle.request} />
            )}
            {isStale && <p className="admin__sr" role="status">Loading events…</p>}

            {!isStale && totalPages > 1 && (
              <nav className="admin__pager" aria-label="Event pages">
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

      {lifecycle.pending && (
        <ConfirmDialog
          title={lifecycle.dialogCopy.title}
          message={lifecycle.dialogCopy.message}
          confirmLabel={lifecycle.dialogCopy.confirmLabel}
          tone={lifecycle.dialogCopy.tone ?? 'primary'}
          busy={lifecycle.submitting}
          onConfirm={handleActionConfirmed}
          onCancel={lifecycle.dismiss}
        />
      )}
    </main>
  )
}