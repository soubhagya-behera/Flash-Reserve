import { useCallback, useState } from 'react'
import { ApiError } from '../../services/apiClient.js'

const ACTION_COPY = {
  publish: {
    title: 'Publish this event?',
    message:
      'The event becomes visible in the public catalog immediately. ' +
      'There is no unpublish action — a published event can only be cancelled.',
    confirmLabel: 'Publish event',
  },
  cancel: {
    title: 'Cancel this event?',
    message:
      'Cancellation is destructive: the event leaves the public catalog and ' +
      'cannot be republished afterwards.',
    confirmLabel: 'Cancel event',
    tone: 'danger',
  },
}

/**
 * Shared publish/cancel mutation state for the admin list and
 * detail pages. `request(action, event)` opens the confirmation
 * dialog; `confirm()` runs the executor against the real backend;
 * `dismiss()` closes without doing anything. The caller re-fetches
 * real data on success — status is never faked locally.
 */
export default function useEventAction(executor) {
  const [pending, setPending] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [actionError, setActionError] = useState(null)
  const [successMessage, setSuccessMessage] = useState(null)

  const request = useCallback((action, event) => {
    setActionError(null)
    setSuccessMessage(null)
    setPending({ action, event })
  }, [])

  const dismiss = useCallback(() => {
    setPending(null)
    setActionError(null)
  }, [])

  const clearSuccess = useCallback(() => setSuccessMessage(null), [])

  const confirm = useCallback(async () => {
    if (!pending || submitting) return
    setSubmitting(true)
    setActionError(null)
    try {
      await executor(pending.action, pending.event)
      setPending(null)
      setSuccessMessage(
        pending.action === 'publish'
          ? `“${pending.event.name}” is now published.`
          : `“${pending.event.name}” has been cancelled.`,
      )
    } catch (error) {
      setActionError(
        error instanceof ApiError
          ? error.message
          : 'Something went wrong. Please try again.',
      )
    } finally {
      setSubmitting(false)
    }
  }, [executor, pending, submitting])

  return {
    pending,
    dialogCopy: pending ? ACTION_COPY[pending.action] : null,
    submitting,
    actionError,
    successMessage,
    request,
    confirm,
    dismiss,
    clearSuccess,
  }
}