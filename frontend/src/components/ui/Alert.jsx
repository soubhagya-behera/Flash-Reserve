import './alert.css'

/**
 * Inline status banner. `role="alert"` makes newly rendered
 * messages announce themselves to assistive technology.
 */
export default function Alert({ tone = 'error', children }) {
  return (
    <div className={`fr-alert fr-alert--${tone}`} role="alert">
      <span className="fr-alert__mark" aria-hidden="true">
        !
      </span>
      <p className="fr-alert__text">{children}</p>
    </div>
  )
}
