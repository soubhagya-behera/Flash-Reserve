import { Link } from 'react-router-dom'

/**
 * 403-style state shown when an authenticated USER reaches an
 * admin route. The interface itself is never rendered — this page
 * replaces it entirely.
 */
export default function AccessDeniedPage() {
  return (
    <main id="main" className="admin fr-anim-fade-in">
      <div className="fr-container">
        <div className="admin__state admin__state--denied fr-surface-gradient">
          <p className="admin__eyebrow">FlashReserve · Administrators only</p>
          <h1 className="fr-heading">Access denied</h1>
          <p className="fr-small admin__state-detail">
            Your account does not have the administrator role required to
            manage events. If you believe this is a mistake, contact the
            FlashReserve team.
          </p>
          <Link to="/dashboard" className="fr-btn fr-btn--primary">
            Back to your dashboard
          </Link>
        </div>
      </div>
    </main>
  )
}