import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { applySeatUpdate, isSeatStatusEvent, seatUpdatesUrl, versionsFromSnapshot } from './seatUpdates.js'

describe('seatUpdates reducer', () => {
  it('builds the stream url for an event', () => {
    assert.equal(seatUpdatesUrl('abc'), '/api/events/abc/seat-updates')
  })
  it('applies AVAILABLE to HELD', () => {
    const seats = [{ id: 's1', seatNumber: 'S008', status: 'AVAILABLE' }]
    const r = applySeatUpdate(seats, { seatId: 's1', seatNumber: 'S008', status: 'HELD', seatVersion: 1 })
    assert.equal(r.applied, true)
    assert.equal(r.seats[0].status, 'HELD')
  })
  it('ignores stale versions', () => {
    const seats = [{ id: 's1', seatNumber: 'S008', status: 'BOOKED' }]
    const versions = new Map([['s1', 12]])
    const r = applySeatUpdate(seats, { seatId: 's1', seatNumber: 'S008', status: 'HELD', seatVersion: 11 }, versions)
    assert.equal(r.applied, false)
    assert.equal(r.seats[0].status, 'BOOKED')
  })
  it('applies HELD to BOOKED and BOOKED to AVAILABLE', () => {
    let seats = [{ id: 's1', seatNumber: 'S008', status: 'HELD' }]
    let versions = new Map()
    let r = applySeatUpdate(seats, { seatId: 's1', seatNumber: 'S008', status: 'BOOKED', seatVersion: 2 }, versions)
    assert.equal(r.seats[0].status, 'BOOKED')
    r = applySeatUpdate(r.seats, { seatId: 's1', seatNumber: 'S008', status: 'AVAILABLE', seatVersion: 3 }, r.versions)
    assert.equal(r.seats[0].status, 'AVAILABLE')
  })
  it('rejects invalid events', () => {
    assert.equal(isSeatStatusEvent({ seatId: 's1' }), false)
    assert.equal(isSeatStatusEvent({ seatId: 's1', seatNumber: 'S1', status: 'HELD' }), false)
  })
  it('snapshot seeds version map', () => {
    const seats = [
      { id: 's1', seatNumber: 'S001', status: 'AVAILABLE', seatVersion: 5 },
      { id: 's2', seatNumber: 'S002', status: 'HELD', seatVersion: 12 },
    ]
    const m = versionsFromSnapshot(seats)
    assert.equal(m.get('s1'), 5)
    assert.equal(m.get('s2'), 12)
  })
  it('stale buffered event cannot overwrite fresh snapshot', () => {
    const snapshot = [{ id: 's1', seatNumber: 'S008', status: 'AVAILABLE', seatVersion: 12 }]
    const versions = versionsFromSnapshot(snapshot)
    const stale = { seatId: 's1', seatNumber: 'S008', status: 'HELD', seatVersion: 11 }
    const r = applySeatUpdate(snapshot, stale, versions)
    assert.equal(r.applied, false)
    assert.equal(r.seats[0].status, 'AVAILABLE')
  })
  it('newer buffered event updates snapshot state', () => {
    const snapshot = [{ id: 's1', seatNumber: 'S008', status: 'AVAILABLE', seatVersion: 12 }]
    const versions = versionsFromSnapshot(snapshot)
    const newer = { seatId: 's1', seatNumber: 'S008', status: 'BOOKED', seatVersion: 13 }
    const r = applySeatUpdate(snapshot, newer, versions)
    assert.equal(r.applied, true)
    assert.equal(r.seats[0].status, 'BOOKED')
  })
  it('reconnect snapshot re-establishes maxAppliedVersion', () => {
    const initial = [{ id: 's1', seatNumber: 'S008', status: 'AVAILABLE', seatVersion: 5 }]
    let versions = versionsFromSnapshot(initial)
    const fresh = [{ id: 's1', seatNumber: 'S008', status: 'HELD', seatVersion: 9 }]
    versions = versionsFromSnapshot(fresh)
    assert.equal(versions.get('s1'), 9)
    const oldEvent = { seatId: 's1', seatNumber: 'S008', status: 'AVAILABLE', seatVersion: 6 }
    const r = applySeatUpdate(fresh, oldEvent, versions)
    assert.equal(r.applied, false)
  })
})
