import './floating-status-card.css'

/**
 * Small floating status chip used around the hero reservation
 * visual. Purely decorative — always aria-hidden.
 */
export default function FloatingStatusCard({
  tone = 'primary',
  mark = null,
  className = '',
  children,
}) {
  return (
    <div className={`fsc fsc--${tone}${className ? ` ${className}` : ''}`} aria-hidden="true">
      {mark ? <span className="fsc__mark">{mark}</span> : null}
      <span className="fsc__label">{children}</span>
    </div>
  )
}
