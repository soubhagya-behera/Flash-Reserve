import useRevealOnScroll from '../../hooks/useRevealOnScroll.js'
import { capabilities } from '../../data/landing.js'
import './sections.css'

export default function CapabilityStrip() {
  const revealRef = useRevealOnScroll()

  return (
    <section className="cap" aria-label="What FlashReserve guarantees">
      <div className="fr-container">
        <ul className="cap__list fr-reveal" ref={revealRef}>
          {capabilities.map((capability) => (
            <li key={capability.title} className="cap__item">
              <p className="cap__title">
                <span className="cap__tick" aria-hidden="true">
                  ✓
                </span>
                {capability.title}
              </p>
              <p className="cap__detail">{capability.detail}</p>
            </li>
          ))}
        </ul>
      </div>
    </section>
  )
}
