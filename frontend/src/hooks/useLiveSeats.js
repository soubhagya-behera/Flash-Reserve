import { useCallback, useEffect, useRef } from 'react'
import {
  applySeatUpdate,
  isSeatStatusEvent,
  shouldShowRemoteConflict,
  versionsFromSnapshot,
} from '../services/seatUpdates.js'
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
  reservation,
  reserving,
}) {
  const seatVersionsRef = useRef(new Map())
  const seatsLiveRef = useRef(seats)
  seatsLiveRef.current = seats
  const reservationRef = useRef(reservation)
  reservationRef.current = reservation
  const reservingRef = useRef(reserving)
  reservingRef.current = reserving

  const seedFromSnapshot = useCallback(
    (seatList) => {
      const list = seatList ?? []
      seatVersionsRef.current = versionsFromSnapshot(list)
      seatsLiveRef.current = list
      setSeats(list)
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
        const ownedSeatId = reservationRef.current?.seatId ?? null
        const reserving = Boolean(reservingRef.current)
        if (
          shouldShowRemoteConflict({
            incoming,
            selectedSeatId: current,
            ownedSeatId,
            reserving,
          })
        ) {
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
        const list = freshSeats ?? []
        seatVersionsRef.current = versionsFromSnapshot(list)
        seatsLiveRef.current = list
        setSeats(list)
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
