import { useCallback, useEffect, useRef } from 'react'
import { applySeatUpdate, isSeatStatusEvent, versionsFromSnapshot } from '../services/seatUpdates.js'
import { useSeatUpdates } from './useSeatUpdates.js'
import * as eventService from '../services/eventService.js'

/**
 * Live seat synchronization for EventDetailPage.
 * Owns the authoritative version map seeded from REST snapshots and
 * merges SSE seat-status events. Clearing of a locally selected seat
 * that becomes HELD/BOOKED is handled here so the page stays lean.
 */
export function useLiveSeats({
  eventId,
  enabled,
  seats,
  setSeats,
  setSelectedSeatId,
  setReservationError,
  onUnknownSeat,
}) {
  const seatVersionsRef = useRef(new Map())
  const seatsLiveRef = useRef(seats)
  seatsLiveRef.current = seats

  const seedFromSnapshot = useCallback(
    (seatList) => {
      seatVersionsRef.current = versionsFromSnapshot(seatList)
      setSeats(seatList ?? [])
    },
    [setSeats],
  )

  useSeatUpdates({
    eventId,
    enabled,
    onEvent: (incoming) => {
      if (!isSeatStatusEvent(incoming)) return
      const result = applySeatUpdate(seatsLiveRef.current ?? [], incoming, seatVersionsRef.current)
      seatVersionsRef.current = result.versions
      if (!result.applied) {
        if (result.unknown) onUnknownSeat?.()
        return
      }
      setSeats(result.seats)
      setSelectedSeatId((current) => {
        if (current && incoming.seatId === current && incoming.status !== 'AVAILABLE') {
          const verb = incoming.status === 'HELD' ? 'reserved' : 'booked'
          setReservationError(`Seat ${incoming.seatNumber} was just ${verb} by another user.`)
          return null
        }
        return current
      })
    },
    onReconnect: async () => {
      try {
        const freshSeats = await eventService.listEventSeats(eventId)
        seatVersionsRef.current = versionsFromSnapshot(freshSeats)
        setSeats(freshSeats ?? [])
      } catch {
        // keep live stream; next reconnect refetches
      }
    },
  })

  // keep seatsLiveRef fresh without extra effect
  useEffect(() => {
    seatsLiveRef.current = seats
  }, [seats])

  return { seedFromSnapshot, seatVersionsRef }
}
