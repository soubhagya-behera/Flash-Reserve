import useRevealOnScroll from '../../hooks/useRevealOnScroll.js'
import { steps } from '../../data/landing.js'
import './sections.css'

export default function HowItWorks() {
  const headRef = useRevealOnScroll()
  const gridRef = useRevealOnScroll()

  return (
    <section className="fr-section" id="how-it-works">
      <div className="fr-container">
        <header className="section-head fr-reveal" ref={headRef}>
          <p className="section-eyebrow">How FlashReserve works</p>
          <h2 className="fr-heading section-title">From event to confirmed seat</h2>
          <p className="section-lede">
            Four calm steps instead of one frantic refresh. The seat you
            choose stays yours while you decide.
          </p>
        </header>

        <ol className="steps fr-reveal" ref={gridRef}>
          {steps.map((step) => (
            <li key={step.number} className="step">
              <span className="step__number" aria-hidden="true">
                {step.number}
              </span>
              <h3 className="fr-subheading step__title">{step.title}</h3>
              <p className="step__detail">{step.detail}</p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  )
}
