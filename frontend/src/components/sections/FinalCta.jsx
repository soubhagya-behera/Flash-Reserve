import Button from '../ui/Button.jsx'
import useRevealOnScroll from '../../hooks/useRevealOnScroll.js'
import './features.css'

export default function FinalCta() {
  const revealRef = useRevealOnScroll()

  return (
    <section className="fr-section">
      <div className="fr-container">
        <div className="fr-surface-gradient cta__panel fr-reveal" ref={revealRef}>
          <span className="cta__glow" aria-hidden="true" />
          <p className="section-eyebrow">Ready when the doors open</p>
          <h2 className="fr-heading cta__title">
            Your seat shouldn&apos;t be a race against a refresh button.
          </h2>
          <p className="cta__tagline">Reserve with FlashReserve.</p>
          <Button href="#top">Explore events</Button>
        </div>
      </div>
    </section>
  )
}
