import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import EventForm from '../../components/admin/EventForm.jsx'
import Alert from '../../components/ui/Alert.jsx'
import { ApiError, toFormErrorState } from '../../services/apiClient.js'
import * as eventService from '../../services/eventService.js'
import './admin.css'

const GENERIC_ERROR = 'Something went wrong. Please try again.'

/**
 * One page component for both form routes:
 *   /admin/events/new            -> create (POST, becomes DRAFT)
 *   /admin/events/:eventId/edit  -> edit   (PUT, full replacement)
 * All HTTP traffic lives in the service layer; this page only owns
 * loading state, navigation and success/error handling.
 */
export default function AdminEventFormPage() {
  const { eventId } = useParams()
  const isEdit = Boolean(eventId)
  const navigate = useNavigate()
  const location = useLocation()

  const [event, setEvent] = useState(null)
  const [loadError, setLoadError] = useState(null)
  const [notFound, setNotFound] = useState(false)
  const [loading, setLoading] = useState(isEdit)
  const [submitting, setSubmitting] = useState(false)
  const [formError, setFormError] = useState(null)
  const [serverFieldErrors, setServerFieldErrors] = useState({})

  useEffect(() => {
    if (!isEdit) return undefined
    const controller = new AbortController()
    let active = true

    eventService
      .getAdminEvent(eventId, { signal: controller.signal })
      .then((result) => {
        if (!active) return
        setEvent(result)
        setLoading(false)
      })
      .catch((error) => {
        if (error instanceof DOMException && error.name === 'AbortError') return
        if (!active) return
        if (error instanceof ApiError && error.status === 404) {
          setNotFound(true)
        } else {
          setLoadError(error instanceof ApiError ? error.message : GENERIC_ERROR)
        }
        setLoading(false)
      })

    return () => {
      active = false
      controller.abort()
    }
  }, [eventId, isEdit])

  async function handleSubmit(payload) {
    setSubmitting(true)
    setFormError(null)
    setServerFieldErrors({})
    try {
      if (isEdit) {
        const updated = await eventService.updateAdminEvent(eventId, payload)
        navigate(`/admin/events/${updated.id}`, {
          replace: true,
          state: { flash: `“${updated.name}” was saved successfully.` },
        })
      } else {
        const created = await eventService.createAdminEvent(payload)
        navigate(`/admin/events/${created.id}`, {
          replace: true,
          state: {
            flash: `“${created.name}” was created as a draft. Publish it when it is ready for the public catalog.`,
          },
        })
      }
    } catch (error) {
      const state = toFormErrorState(error)
      setFormError(state.message)
      setServerFieldErrors(state.fieldErrors)
    } finally {
      setSubmitting(false)
    }
  }

  const flash = location.state?.flash ?? null

  if (loading) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <p className="admin__sr" role="status">Loading event…</p>
          <div className="admin-form-card fr-surface-elevated">
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
              The event you are trying to edit does not exist (or its link is malformed).
            </p>
            <Link to="/admin/events" className="fr-btn fr-btn--primary">
              Back to all events
            </Link>
          </div>
        </div>
      </main>
    )
  }

  if (loadError) {
    return (
      <main id="main" className="admin fr-anim-fade-in">
        <div className="fr-container">
          <div className="admin__state fr-surface-gradient">
            <h1 className="fr-heading">We couldn&apos;t load this event</h1>
            <p className="fr-small admin__state-detail">{loadError}</p>
            <Link to="/admin/events" className="fr-btn fr-btn--primary">
              Back to all events
            </Link>
          </div>
        </div>
      </main>
    )
  }

  return (
    <main id="main" className="admin fr-anim-fade-in">
      <div className="fr-container admin-form-container">
        <Link to={isEdit ? `/admin/events/${eventId}` : '/admin/events'} className="admin__back">
          <span aria-hidden="true">←</span>{' '}
          {isEdit ? 'Back to event' : 'Back to all events'}
        </Link>

        <header className="admin__header admin__header--form">
          <p className="admin__eyebrow">FlashReserve · Administration</p>
          <h1 className="fr-heading admin__title">
            {isEdit ? `Edit “${event?.name ?? ''}”` : 'Create event'}
          </h1>
          <p className="admin__lead">
            {isEdit
              ? 'Replaces the editable fields of the event. Status changes happen from the event page.'
              : 'New events always start as drafts — nothing is public until you publish.'}
          </p>
        </header>

        {flash && (
          <div role="status">
            <Alert tone="info">{flash}</Alert>
          </div>
        )}

        <div className="admin-form-card fr-surface-elevated fr-anim-fade-up">
          <EventForm
            mode={isEdit ? 'edit' : 'create'}
            initial={isEdit ? event : null}
            submitting={submitting}
            formError={formError}
            serverFieldErrors={serverFieldErrors}
            onSubmit={handleSubmit}
          />
        </div>
      </div>
    </main>
  )
}