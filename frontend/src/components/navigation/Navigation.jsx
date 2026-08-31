import { useState } from 'react'
import Button from '../ui/Button.jsx'
import { navLinks } from '../../data/landing.js'
import './navigation.css'

export default function Navigation() {
  const [menuOpen, setMenuOpen] = useState(false)

  const closeMenu = () => setMenuOpen(false)

  return (
    <header className="nav">
      <div className="fr-container nav__inner">
        <a href="#top" className="nav__brand" onClick={closeMenu}>
          <span className="fr-mark fr-gradient-brand" aria-hidden="true">
            F
          </span>
          <span className="nav__wordmark">FlashReserve</span>
        </a>

        <div
          id="primary-navigation"
          className={`nav__menu${menuOpen ? ' nav__menu--open' : ''}`}
        >
          <nav aria-label="Primary">
            <ul className="nav__links">
              {navLinks.map((link) => (
                <li key={link.label}>
                  <a href={link.href} onClick={closeMenu}>
                    {link.label}
                  </a>
                </li>
              ))}
            </ul>
          </nav>

          <div className="nav__actions">
            {/* Visual placeholders — authentication arrives in a later commit. */}
            <Button href="#" variant="ghost" className="fr-btn--compact" tabIndex={menuOpen ? 0 : undefined}>
              Sign in
            </Button>
            <Button href="#" variant="primary" className="fr-btn--compact" tabIndex={menuOpen ? 0 : undefined}>
              Get started
            </Button>
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
