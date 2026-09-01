import { Link } from 'react-router-dom'
import Button from '../ui/Button.jsx'
import ReservationVisual from './ReservationVisual.jsx'
import { heroFlow } from '../../data/landing.js'
import './hero.css'

export default function Hero() {
  return (
    <section className="hero" id="top">
      <div className="fr-container hero__inner">
        <div className="hero__copy">
          <p className="hero__eyebrow fr-anim-fade-in">
            <span className="hero__eyebrow-dot fr-anim-pulse" aria-hidden="true" />
            FlashReserve · Live reservations
          </p>

          <h1 className="fr-display hero__title fr-anim-fade-up" style={{ animationDelay: '90ms' }}>
            Reserve the&nbsp;moment.
          </h1>

          <p className="hero__lead fr-anim-fade-up" style={{ animationDelay: '180ms' }}>
            Fast, reliable reservations for the moments people don&apos;t want
            to miss. Pick your event, lock your seat, and walk in with a
            confirmed ticket.
          </p>

          <div className="hero__cta fr-anim-fade-up" style={{ animationDelay: '270ms' }}>
            <Link to="/events" className="fr-btn fr-btn--primary">
              Explore events
            </Link>
            <Button href="#how-it-works" variant="ghost">
              How it works
            </Button>
          </div>

          <p className="hero__flow fr-anim-fade-in" style={{ animationDelay: '420ms' }}>
            {heroFlow.map((step, index) => (
              <span key={step} className="hero__flow-step">
                {index > 0 && (
                  <span className="hero__flow-arrow" aria-hidden="true">
                    →
                  </span>
                )}
                {step}
              </span>
            ))}
          </p>
        </div>

        <div className="hero__visual fr-anim-fade-in" style={{ animationDelay: '240ms' }}>
          <ReservationVisual />
        </div>
      </div>
    </section>
  )
}
