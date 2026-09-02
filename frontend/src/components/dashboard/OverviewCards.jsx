import { Link } from 'react-router-dom'

/* Every value shown comes from the caller's real backend bookings —
   this component only presents numbers it is handed. */
const CARDS = [
  { key: 'upcoming', label: 'Upcoming', detail: 'Confirmed events ahead' },
  { key: 'pending', label: 'Pending', detail: 'Awaiting payment' },
  { key: 'confirmed', label: 'Confirmed', detail: 'Seats secured' },
  { key: 'total', label: 'All bookings', detail: 'Every reservation' },
]

/**
 * The dashboard's small overview row. `counts` is derived by the page
 * from the user's complete booking list; `loading` swaps the numbers
 * for a calm skeleton of the same shape.
 */
export default function OverviewCards({ counts, loading = false }) {
  if (loading) {
    return (
      <div className="overview" aria-hidden="true">
        {CARDS.map((card) => (
          <div key={card.key} className="overview__card fr-surface">
            <span className="overview__value-skeleton" />
            <span className="overview__label-skeleton" />
          </div>
        ))}
      </div>
    )
  }

  return (
    <div className="overview">
      {CARDS.map((card) => (
        <Link
          key={card.key}
          to="/bookings"
          className={`overview__card fr-surface overview__card--${card.key}`}
        >
          <span className="overview__value">{counts[card.key]}</span>
          <span className="overview__label">{card.label}</span>
          <span className="overview__detail fr-small">{card.detail}</span>
        </Link>
      ))}
    </div>
  )
}
