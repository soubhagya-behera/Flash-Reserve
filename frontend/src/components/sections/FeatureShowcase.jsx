import useRevealOnScroll from '../../hooks/useRevealOnScroll.js'
import { features } from '../../data/landing.js'
import './features.css'

export default function FeatureShowcase() {
  const headRef = useRevealOnScroll()
  const gridRef = useRevealOnScroll()

  return (
    <section className="fr-section features" id="features">
      <div className="fr-container">
        <header className="section-head section-head--center fr-reveal" ref={headRef}>
          <p className="section-eyebrow">Under the hood</p>
          <h2 className="fr-heading section-title">
            Built so the seat you pick is the seat you get
          </h2>
          <p className="section-lede">
            FlashReserve is engineered for the hardest part of ticketing:
            everyone wanting the same seat at the same time.
          </p>
        </header>

        <div className="features__grid fr-reveal" ref={gridRef}>
          {/* Spotlight: the headline capability gets a room of its own. */}
          <article className="feature feature--spotlight">
            <div className="feature__body">
              <h3 className="fr-subheading feature__title">{features[0].title}</h3>
              <p className="feature__detail">{features[0].detail}</p>
            </div>

            <div className="feature__scene" aria-hidden="true">
              <div className="feature__mini-map">
                {Array.from({ length: 8 }, (_, index) => (
                  <span
                    key={index}
                    className={`feature__mini-seat${index === 4 ? ' feature__mini-seat--locked' : ''}`}
                  />
                ))}
              </div>
              <span className="feature__lock-chip fr-anim-pulse">Locked for you</span>
            </div>
          </article>

          {features.slice(1).map((feature) => (
            <article key={feature.title} className="feature">
              <h3 className="fr-subheading feature__title">{feature.title}</h3>
              <p className="feature__detail">{feature.detail}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  )
}
