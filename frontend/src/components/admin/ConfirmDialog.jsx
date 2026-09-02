import { useEffect, useRef } from 'react'
import Button from '../ui/Button.jsx'
import './admin-widgets.css'

/**
 * Modal confirmation for destructive or state-changing actions.
 * Keyboard accessible: focus lands on the confirm button, Tab is
 * trapped between the two dialog buttons, Escape cancels (unless a
 * submission is in flight) and the overlay itself is inert.
 */
export default function ConfirmDialog({
  title,
  message,
  confirmLabel = 'Confirm',
  tone = 'primary',
  busy = false,
  onConfirm,
  onCancel,
}) {
  const confirmRef = useRef(null)

  useEffect(() => {
    confirmRef.current?.focus()
  }, [])

  function handleKeyDown(event) {
    if (event.key === 'Escape' && !busy) {
      event.stopPropagation()
      onCancel()
      return
    }
    // Minimal focus trap: the dialog has exactly two focusables.
    if (event.key === 'Tab') {
      event.preventDefault()
      confirmRef.current?.closest('.admin-dialog__panel')?.querySelectorAll('button').forEach((button, index, all) => {
        if (button === document.activeElement) {
          all[(index + 1) % all.length].focus()
        }
      })
    }
  }

  return (
    <div className="admin-dialog" onKeyDown={handleKeyDown}>
      <div className="admin-dialog__backdrop" aria-hidden="true" />
      <div
        className="admin-dialog__panel fr-surface-elevated fr-anim-fade-up"
        role="dialog"
        aria-modal="true"
        aria-labelledby="admin-dialog-title"
        aria-describedby="admin-dialog-message"
      >
        <h2 className="fr-subheading" id="admin-dialog-title">
          {title}
        </h2>
        <p className="fr-small admin-dialog__message" id="admin-dialog-message">
          {message}
        </p>
        <div className="admin-dialog__actions">
          <Button variant="ghost" onClick={onCancel} disabled={busy}>
            Keep as is
          </Button>
          <Button
            ref={confirmRef}
            variant={tone}
            onClick={onConfirm}
            disabled={busy}
            aria-busy={busy}
          >
            {busy ? 'Working…' : confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  )
}