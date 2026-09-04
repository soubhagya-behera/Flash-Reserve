import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import ConfirmDialog from '../../components/admin/ConfirmDialog.jsx'
import StatusBadge from '../../components/admin/StatusBadge.jsx'
import useEventAction from '../../components/admin/useEventAction.js'
import Alert from '../../components/ui/Alert.jsx'
import Button from '../../components/ui/Button.jsx'
import { ApiError } from '../../services/apiClient.js'
import * as eventService from '../../services/eventService.js'
import {
  formatEventDate,
  formatEventTime,
  formatTicketPrice,
} from '../../utils/format.js'
import './admin.css'

const GENERIC_ERROR = 'Something went wrong while loading this event. Please try again.'

/**
 * Admin detail view for a single event, fetched from the ADMIN
 * endpoint so drafts and cancelled events are reachable here too.
 * Lifecycle actions follow the real backend contract and always
 * re-fetch the server's answer afterwards.
 */
export default function AdminEventDetailPage() {
  const { eventId } = useParams()
  const location = useLocation()
  const [event, setEvent] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [resolvedAttempt, setResolvedAttempt] = useState(-1)
  const [outcome, setOutcome] = useState(null) // null | 'not-found' | 'error'
  const [loadError, setLoadError] = useState(null)

  // Derived, never set synchronously inside the effect: a fetch is
  // in flight while its attempt has not resolved yet.
  const loading = resolvedAttempt !== attempt
  const notFound = !loading && outcome === 'not-found'

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    eventService
      .getAdminEvent(eventId, { signal: controller.signal })
      .then((result) => {
        if (!active) return
        setEvent(result)
        setLoadError(null)
        setOutcome(null)
        setResolvedAttempt(attempt)
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
        if (error instanceof ApiError && error.status === 404) {
          setOutcome('not-found')
        } else {
          setLoadError(error instanceof ApiError ? error.message : GENERIC_ERROR)
          setOutcome('error')
        }
        setResolvedAttempt(attempt)
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [eventId, attempt])

  const executor = useCallback(async (action, current) => {
    if (action === 'publish') {
      await eventService.publishAdminEvent(current.id)
    } else {
      await eventService.cancelAdminEvent(current.id)
    }
  }, [])

  const lifecycle = useEventAction(executor)

  const handleActionConfirmed = async () => {
    await lifecycle.confirm()
    // The shown status must come from the backend, never from a
    // local assumption — re-fetch the real event after a mutation.
    setAttempt((current) => current + 1)
  }

  const flash = location.state?.flash ?? null

  if (loading) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <p className="admin__sr" role="status">Loading event…</p>
          <div className="admin-detail-card fr-surface-elevated">
            <div className="admin-form__skeleton" aria-hidden="true">
              {Array.from({ length: 6 }, (_, index) => (
                <span key={index} className="admin-form__skeleton-line" />
              ))}
            </div>
          </div>
        </div>
      </main>
    )
  }

  if (notFound) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <div className="admin__state fr-surface-gradient">
            <h1 className="fr-heading">Event not found</h1>
            <p className="fr-small admin__state-detail">
              No event exists with this id. It may have been a mistyped or
              stale link.
            </p>
            <Link to="/admin/events" className="fr-btn fr-btn--primary">
              Back to all events
            </Link>
          </div>
        </div>
      </main>
    )
  }

  if (loadError || !event) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <div className="admin__state fr-surface-gradient">
            <h1 className="fr-heading">We couldn&apos;t load this event</h1>
            <p className="fr-small admin__state-detail">{loadError ?? GENERIC_ERROR}</p>
            <Button onClick={() => setAttempt((current) => current + 1)}>Try again</Button>
          </div>
        </div>
      </main>
    )
  }

  const canPublish = event.status === 'DRAFT'
  const canCancel = event.status === 'DRAFT' || event.status === 'PUBLISHED'

  return (
    <main id="main" className="admin fr-anim-fade-in">
      <div className="fr-container admin-detail-container">
        <Link to="/admin/events" className="admin__back">
          <span aria-hidden="true">←</span> Back to all events
        </Link>

        <header className="admin-detail-head">
          <div>
            <p className="admin__eyebrow">FlashReserve · Administration</p>
            <h1 className="fr-heading admin-detail-title">{event.name}</h1>
            <p className="admin-detail-meta">
              <StatusBadge status={event.status} />
              <span className="fr-caption">
                Created {formatEventDate(event.createdAt) || '—'}
              </span>
            </p>
          </div>
          <div className="admin-detail-actions">
            <Link
              to={`/admin/events/${event.id}/edit`}
              className="fr-btn fr-btn--ghost"
            >
              Edit
            </Link>
            {canPublish && (
              <Button onClick={() => lifecycle.request('publish', event)}>
                Publish
              </Button>
            )}
            {canCancel && (
              <Button
                variant="danger"
                onClick={() => lifecycle.request('cancel', event)}
              >
                Cancel event
              </Button>
            )}
          </div>
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

        <div className="admin-detail-card fr-surface-elevated fr-anim-fade-up">
          <dl className="admin-detail-grid">
            <div className="admin-detail-field admin-detail-field--wide">
              <dt>Description</dt>
              <dd>
                {event.description
                  ? event.description
                  : 'No description provided.'}
              </dd>
            </div>

            <div className="admin-detail-field">
              <dt>Venue</dt>
              <dd>{event.venue}</dd>
            </div>

            <div className="admin-detail-field">
              <dt>Date &amp; time</dt>
              <dd>
                {formatEventDate(event.eventDate)}
                {formatEventTime(event.eventDate)
                  ? ` · ${formatEventTime(event.eventDate)}`
                  : ''}
              </dd>
            </div>

            <div className="admin-detail-field">
              <dt>Ticket price</dt>
              <dd>{formatTicketPrice(event.ticketPrice) ?? '—'}</dd>
            </div>

            <div className="admin-detail-field">
              <dt>Total seats</dt>
              <dd>{event.totalSeats}</dd>
            </div>

            <div className="admin-detail-field">
              <dt>Status</dt>
              <dd>
                <StatusBadge status={event.status} />
              </dd>
            </div>

            <div className="admin-detail-field">
              <dt>Created</dt>
              <dd>
                {formatEventDate(event.createdAt)}
                {formatEventTime(event.createdAt)
                  ? ` · ${formatEventTime(event.createdAt)}`
                  : ''}
              </dd>
            </div>

            <div className="admin-detail-field admin-detail-field--wide admin-detail-field--mono">
              <dt>Event ID</dt>
              <dd>
                <code>{event.id}</code>
              </dd>
            </div>
          </dl>
        </div>
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