import { useState } from 'react'
import Alert from '../ui/Alert.jsx'
import Button from '../ui/Button.jsx'
import TextAreaField from '../ui/TextAreaField.jsx'
import TextField from '../ui/TextField.jsx'
import {
  dateTimeLocalToInstant,
  instantToDateTimeLocal,
  validateEventDate,
  validateEventDescription,
  validateEventName,
  validateEventVenue,
  validateTicketPrice,
  validateTotalSeats,
} from '../../utils/eventValidation.js'
import './admin-widgets.css'

function emptyForm() {
  return { name: '', description: '', venue: '', eventDate: '', totalSeats: '', ticketPrice: '' }
}

function toDecimalString(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? String(parsed) : value
}

/**
 * Shared create/edit event form. Validates locally against the same
 * constraints as the backend DTOs, then hands a clean payload to the
 * page: eventDate as a real ISO-8601 instant (the browser converts
 * the local datetime-local value), ticketPrice as a decimal string.
 * totalSeats is only collected in create mode — the backend generates
 * the seat inventory once, at creation, and never allows changing it.
 */
export default function EventForm({
  mode,
  initial = null,
  submitting = false,
  formError = null,
  serverFieldErrors = {},
  onSubmit,
}) {
  const [form, setForm] = useState(() =>
    initial
      ? {
          name: initial.name ?? '',
          description: initial.description ?? '',
          venue: initial.venue ?? '',
          eventDate: instantToDateTimeLocal(initial.eventDate),
          totalSeats: initial.totalSeats != null ? String(initial.totalSeats) : '',
          ticketPrice:
            initial.ticketPrice != null ? toDecimalString(initial.ticketPrice) : '',
        }
      : emptyForm(),
  )
  const [fieldErrors, setFieldErrors] = useState({})
  const isEdit = mode === 'edit'

  const setField = (field) => (event) => {
    const value = event.target.value
    setForm((current) => ({ ...current, [field]: value }))
    setFieldErrors((current) => ({ ...current, [field]: null }))
  }

  async function handleSubmit(event) {
    event.preventDefault()
    if (submitting) return

    const nextErrors = {
      name: validateEventName(form.name),
      description: validateEventDescription(form.description),
      venue: validateEventVenue(form.venue),
      eventDate: validateEventDate(form.eventDate, {
        initialInstant: isEdit ? initial?.eventDate ?? null : null,
      }),
      ...(isEdit ? {} : { totalSeats: validateTotalSeats(form.totalSeats) }),
      ticketPrice: validateTicketPrice(form.ticketPrice),
    }
    setFieldErrors(nextErrors)
    if (Object.values(nextErrors).some(Boolean)) return

    const payload = {
      name: form.name.trim(),
      description: form.description.trim() || null,
      venue: form.venue.trim(),
      eventDate: dateTimeLocalToInstant(form.eventDate),
      ticketPrice: toDecimalString(form.ticketPrice),
    }
    if (!isEdit) payload.totalSeats = Number(form.totalSeats)

    await onSubmit(payload)
  }

  const mergedError = (field) => fieldErrors[field] ?? serverFieldErrors[field] ?? null

  return (
    <form
      className="admin-form"
      onSubmit={handleSubmit}
      noValidate
      aria-busy={submitting}
    >
      {formError ? <Alert>{formError}</Alert> : null}

      <TextField
        id="event-name"
        label="Name"
        value={form.name}
        onChange={setField('name')}
        error={mergedError('name')}
        disabled={submitting}
        maxLength={200}
        placeholder="e.g. Monsoon Open-Air Concert"
      />

      <TextAreaField
        id="event-description"
        label="Description (optional)"
        value={form.description}
        onChange={setField('description')}
        error={mergedError('description')}
        disabled={submitting}
        maxLength={2000}
        placeholder="What makes this event worth showing up for?"
      />

      <TextField
        id="event-venue"
        label="Venue"
        value={form.venue}
        onChange={setField('venue')}
        error={mergedError('venue')}
        disabled={submitting}
        maxLength={255}
        placeholder="e.g. City Arena, Hall 2"
      />

      <TextField
        id="event-date"
        label={isEdit ? 'Date & time' : 'Date & time (must be in the future)'}
        type="datetime-local"
        value={form.eventDate}
        onChange={setField('eventDate')}
        error={mergedError('eventDate')}
        disabled={submitting}
      />

      {!isEdit ? (
        <TextField
          id="event-seats"
          label="Total seats"
          type="number"
          min="1"
          max="10000"
          step="1"
          value={form.totalSeats}
          onChange={setField('totalSeats')}
          error={mergedError('totalSeats')}
          disabled={submitting}
          placeholder="e.g. 120"
        />
      ) : (
        <div className="fr-field">
          <span className="fr-field__label" id="event-seats-readonly-label">
            Total seats
          </span>
          <p className="admin-form__readonly" aria-labelledby="event-seats-readonly-label">
            {form.totalSeats || '—'}
          </p>
          <p className="fr-caption admin-form__note">
            Fixed at creation — seats are generated once and can never be changed.
          </p>
        </div>
      )}

      <TextField
        id="event-price"
        label="Ticket price (INR)"
        type="number"
        min="0.01"
        step="0.01"
        value={form.ticketPrice}
        onChange={setField('ticketPrice')}
        error={mergedError('ticketPrice')}
        disabled={submitting}
        placeholder="e.g. 499.00"
      />

      <Button type="submit" disabled={submitting} aria-busy={submitting}>
        {submitting
          ? isEdit
            ? 'Saving…'
            : 'Creating…'
          : isEdit
            ? 'Save changes'
            : 'Create event'}
      </Button>
    </form>
  )
}