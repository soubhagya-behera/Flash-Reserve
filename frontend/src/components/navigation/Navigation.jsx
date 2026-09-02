import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import Button from '../ui/Button.jsx'
import { useAuth } from '../../auth/authContext.js'
import { navLinks } from '../../data/landing.js'
import './navigation.css'

export default function Navigation() {
  const [menuOpen, setMenuOpen] = useState(false)
  const { isAuthenticated, user, logout } = useAuth()
  const location = useLocation()
  const navigate = useNavigate()

  const closeMenu = () => setMenuOpen(false)

  // Section anchors only resolve on the landing page; from other
  // routes they are prefixed so they navigate back home first.
  const onHome = location.pathname === '/'
  const resolveHref = (href) =>
    href.startsWith('#') && !onHome && href !== '#' ? `/${href}` : href

  function handleSignOut() {
    closeMenu()
    logout()
    navigate('/')
  }

  const firstName = user?.name?.split(' ')[0] ?? ''

  return (
    <header className="nav">
      <div className="fr-container nav__inner">
        <Link to="/" className="nav__brand">
          <span className="fr-mark fr-gradient-brand" aria-hidden="true">
            F
          </span>
          <span className="nav__wordmark">FlashReserve</span>
        </Link>

        <div
          id="primary-navigation"
          className={`nav__menu${menuOpen ? ' nav__menu--open' : ''}`}
        >
          <nav aria-label="Primary">
            <ul className="nav__links">
              {navLinks.map((link) => (
                <li key={link.label}>
                  {link.href.startsWith('/') ? (
                    <Link to={link.href} onClick={closeMenu}>
                      {link.label}
                    </Link>
                  ) : (
                    <a href={resolveHref(link.href)} onClick={closeMenu}>
                      {link.label}
                    </a>
                  )}
                </li>
              ))}
            </ul>
          </nav>

          <div className="nav__actions">
            {isAuthenticated ? (
              <>
                <Link
                  to="/dashboard"
                  className="fr-btn fr-btn--ghost fr-btn--compact"
                  onClick={closeMenu}
                >
                  Dashboard
                </Link>
                <Link
                  to="/bookings"
                  className="fr-btn fr-btn--ghost fr-btn--compact"
                  onClick={closeMenu}
                >
                  My bookings
                </Link>
                {/* Administration entry: administrators only, never
                    shown to normal USER accounts or when anonymous. */}
                {user?.role === 'ADMIN' && (
                  <Link
                    to="/admin/events"
                    className="fr-btn fr-btn--ghost fr-btn--compact"
                    onClick={closeMenu}
                  >
                    Manage events
                  </Link>
                )}
                <span className="nav__user">Hi, {firstName}</span>
                <Button variant="ghost" className="fr-btn--compact" onClick={handleSignOut}>
                  Sign out
                </Button>
              </>
            ) : (
              <>
                <Link
                  to="/login"
                  className="fr-btn fr-btn--ghost fr-btn--compact"
                  onClick={closeMenu}
                >
                  Sign in
                </Link>
                <Link
                  to="/register"
                  className="fr-btn fr-btn--primary fr-btn--compact"
                  onClick={closeMenu}
                >
                  Get started
                </Link>
              </>
            )}
          </div>
        </div>

        <button
          type="button"
          className="nav__toggle"
          aria-expanded={menuOpen}
          aria-controls="primary-navigation"
          onClick={() => setMenuOpen((open) => !open)}
        >
          <span className="nav__toggle-box" aria-hidden="true">
            <span className="nav__toggle-bar" />
            <span className="nav__toggle-bar" />
          </span>
          <span className="nav__toggle-label">{menuOpen ? 'Close' : 'Menu'}</span>
        </button>
      </div>
    </header>
  )
}
