/* ============================================================
   FlashReserve - Seat-update SSE hook
   Owns exactly one EventSource per eventId. Opens after the REST
   snapshot is ready; on error closes, backs off, reconnects once,
   refetches the REST snapshot, then applies buffered frames. No
   heartbeat watchdog: SSE comment heartbeats never reach onmessage;
   onerror drives recovery.
   ============================================================ */

import { useEffect, useRef } from 'react'
import { seatUpdatesUrl } from '../services/seatUpdates.js'

const BACKOFFS = [1000, 2000, 5000, 15000]

export function useSeatUpdates({ eventId, enabled, onEvent, onReconnect }) {
  const sourceRef = useRef(null)
  const timerRef = useRef(null)
  const attemptRef = useRef(0)
  const callbacksRef = useRef({ onEvent, onReconnect })
  callbacksRef.current = { onEvent, onReconnect }

  useEffect(() => {
    if (!enabled || !eventId) return undefined

    let disposed = false
    // While a reconnect refetch is in flight, frames are queued and applied
    // only after the fresh snapshot has landed.
    let buffered = null

    const cleanup = () => {
      if (timerRef.current) {
        clearTimeout(timerRef.current)
        timerRef.current = null
      }
      if (sourceRef.current) {
        sourceRef.current.close()
        sourceRef.current = null
      }
    }

    const deliver = (payload) => {
      try {
        callbacksRef.current.onEvent?.(payload)
      } catch {
        // Malformed frame: ignore, snapshot refetch covers truth.
      }
    }

    const scheduleReconnect = () => {
      if (disposed || timerRef.current) return
      const delay = BACKOFFS[Math.min(attemptRef.current, BACKOFFS.length - 1)]
      attemptRef.current += 1
      timerRef.current = setTimeout(() => {
        timerRef.current = null
        if (!disposed) connect(true)
      }, delay + Math.floor(Math.random() * 250))
    }

    const connect = (isReconnect) => {
      if (disposed) return
      if (sourceRef.current) sourceRef.current.close()
      if (isReconnect) buffered = []
      const source = new EventSource(seatUpdatesUrl(eventId))
      sourceRef.current = source

      source.onopen = async () => {
        attemptRef.current = 0
        if (!isReconnect) return
        try {
          // Refresh the REST snapshot BEFORE the buffered frames are applied,
          // so events emitted while offline cannot overwrite fresher data.
          await callbacksRef.current.onReconnect?.()
        } catch {
          // Keep the live stream; the next reconnect refetches.
        }
        const queued = buffered
        buffered = null
        for (const payload of queued ?? []) deliver(payload)
      }

      const handleMessage = (message) => {
        let payload
        try {
          payload = JSON.parse(message.data)
        } catch {
          return
        }
        if (buffered) {
          buffered.push(payload)
          return
        }
        deliver(payload)
      }

      // Backend sends `event: seat-status`; EventSource delivers named events
      // only to addEventListener, not to onmessage. Listen for both to avoid
      // silent loss of live seat updates (root cause of two-browser desync).
      source.addEventListener('seat-status', handleMessage)
      source.onmessage = handleMessage

      source.onerror = () => {
        source.close()
        if (sourceRef.current === source) sourceRef.current = null
        scheduleReconnect()
      }
    }

    connect(false)

    return () => {
      disposed = true
      cleanup()
    }
  }, [eventId, enabled])
}
