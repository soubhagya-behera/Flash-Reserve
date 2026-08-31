import FloatingStatusCard from './FloatingStatusCard.jsx'
import { seatRows, seatStates } from '../../data/landing.js'
import './reservation-visual.css'

/**
 * The FlashReserve centerpiece: a floating event ticket with a
 * seat map, layered with a confirmation stub and status cards.
 * Presentation only — the seat data in ./data/landing.js is
 * static illustration, never live availability.
 */
export default function ReservationVisual() {
  return (
    <div
      className="rv"
      role="img"
      aria-label="Illustration of a FlashReserve reservation: a live concert ticket for Mumbai at 8:00 PM with a seat map showing seats being held, a confirmation stub, and reservation status cards."
    >
      <div className="rv__stage" aria-hidden="true">
        <span className="rv__glow fr-anim-pulse" />
        <span className="rv__stub rv__stub--back fr-anim-float-slow" />

        <div className="rv__ticket fr-anim-fade-up">
          <div className="rv__ticket-head">
            <div>
              <p className="rv__eyebrow">Event · Live now</p>
              <p className="rv__event">Live Concert</p>
              <p className="rv__meta">Mumbai · Tonight · 8:00 PM</p>
            </div>
            <span className="rv__live">
              <span className="rv__live-dot fr-anim-pulse" />
              Live
            </span>
          </div>

          <div className="rv__ticket-body">
            <p className="rv__map-label">Seat map</p>
            <div className="rv__map">
              {seatRows.map((row) => (
                <div key={row.label} className="rv__row">
                  <span className="rv__row-label">{row.label}</span>
                  <span className="rv__dots">
                    {row.seats.map((seat, index) => (
                      <span
                        key={`${row.label}-${index}`}
                        className={`rv__seat rv__seat--${seat}`}
                      />
                    ))}
                  </span>
                </div>
              ))}
            </div>
          </div>

          <div className="rv__ticket-foot">
            <ul className="rv__legend">
              {seatStates.map((item) => (
                <li key={item.state} className="rv__legend-item">
                  <span className={`rv__seat rv__seat--${item.state}`} />
                  {item.label}
                </li>
              ))}
            </ul>
            <p className="rv__availability">24 seats available</p>
          </div>
        </div>

        <div className="rv__stub rv__stub--front fr-anim-float">
          <p className="rv__stub-flag">
            <span className="rv__stub-check">✓</span> Reserved
          </p>
          <p className="rv__stub-seat">Seat A-24</p>
          <p className="rv__stub-ref">Confirmed · Ref FR-2481</p>
        </div>

        <FloatingStatusCard tone="success" mark="✓" className="rv__float rv__float--confirmed">
          Reservation confirmed
        </FloatingStatusCard>
        <FloatingStatusCard tone="primary" className="rv__float rv__float--seat">
          Seat A-24 reserved
        </FloatingStatusCard>
        <FloatingStatusCard tone="accent" className="rv__float rv__float--hold">
          Hold expires in 09:58
        </FloatingStatusCard>
      </div>
    </div>
  )
}
