import { useMemo } from 'react'
import './seat-map.css'

/* Backend SeatStatus values. Unknown statuses fall back to the
   non-selectable "booked" treatment so nothing can be oversold. */
const STATUS_LABELS = {
  AVAILABLE: 'available',
  HELD: 'held',
  BOOKED: 'booked',
}

const LEGEND_ITEMS = [
  { status: 'AVAILABLE', label: 'Available' },
  { status: 'SELECTED', label: 'Your selection' },
  { status: 'HELD', label: 'Held — may free up' },
  { status: 'BOOKED', label: 'Booked' },
]

/**
 * Presentation-only seat map. Available seats are real toggle
 * buttons; held and booked seats render as disabled controls.
 */
export default function SeatMap({ seats, selectedSeatIds, onToggleSeat }) {
  const selectedIds = useMemo(() => new Set(selectedSeatIds), [selectedSeatIds])
  /* The backend returns seats unordered; the map shows them in
     natural seat-number order (S001, S002, …). */
  const orderedSeats = useMemo(
    () =>
      [...seats].sort((a, b) =>
        a.seatNumber.localeCompare(b.seatNumber, undefined, { numeric: true }),
      ),
    [seats],
  )

  if (!orderedSeats.length) {
    return (
      <p className="seat-map__empty fr-small">
        The seat map for this event hasn&apos;t been released yet.
      </p>
    )
  }

  return (
    <div className="seat-map">
      <ul className="seat-map__legend">
        {LEGEND_ITEMS.map((item) => (
          <li key={item.status} className="seat-map__legend-item">
            <span
              className={`seat-map__swatch seat-map__swatch--${item.status.toLowerCase()}`}
              aria-hidden="true"
            />
            {item.label}
          </li>
        ))}
      </ul>

      <div className="seat-map__grid" role="group" aria-label="Seat availability">
        {orderedSeats.map((seat) => {
          const isSelected = selectedIds.has(seat.id)
          const selectable = seat.status === 'AVAILABLE'
          const stateLabel = isSelected ? 'selected' : (STATUS_LABELS[seat.status] ?? seat.status.toLowerCase())
          const classes = [
            'seat-map__seat',
            `seat-map__seat--${STATUS_LABELS[seat.status] ?? 'booked'}`,
          ]
          if (isSelected) classes.push('seat-map__seat--selected')

          return (
            <button
              key={seat.id}
              type="button"
              className={classes.join(' ')}
              aria-pressed={selectable ? isSelected : undefined}
              aria-label={`Seat ${seat.seatNumber} — ${stateLabel}`}
              disabled={!selectable}
              onClick={() => onToggleSeat(seat.id)}
            >
              {isSelected && <span aria-hidden="true">✓</span>}
              {seat.seatNumber}
            </button>
          )
        })}
      </div>
    </div>
  )
}
