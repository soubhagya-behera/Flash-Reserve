import { Link } from 'react-router-dom'
import { footerLinks } from '../../data/landing.js'
import './footer.css'

export default function Footer() {
  return (
    <footer className="footer">
      <div className="fr-container footer__inner">
        <div className="footer__brand">
          <p className="footer__wordmark">
            <span className="fr-mark fr-gradient-brand" aria-hidden="true">
              F
            </span>
            FlashReserve
          </p>
          <p className="footer__description">
            Fast, reliable reservations for the moments people don&apos;t want
            to miss.
          </p>
        </div>

        <nav aria-label="Footer">
          <ul className="footer__links">
            {footerLinks.map((link) => (
              <li key={link.label}>
                {link.href.startsWith('/') ? (
                  <Link to={link.href}>{link.label}</Link>
                ) : (
                  <a href={link.href}>{link.label}</a>
                )}
              </li>
            ))}
          </ul>
        </nav>

        <p className="footer__note fr-caption">
          © {new Date().getFullYear()} FlashReserve — a full-stack reservation
          experience, built in the open.
        </p>
      </div>
    </footer>
  )
}
