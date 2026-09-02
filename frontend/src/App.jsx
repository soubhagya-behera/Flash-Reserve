import { Navigate, Route, Routes } from 'react-router-dom'
import Navigation from './components/navigation/Navigation.jsx'
import LandingPage from './pages/LandingPage.jsx'
import LoginPage from './pages/LoginPage.jsx'
import RegisterPage from './pages/RegisterPage.jsx'
import EventsPage from './pages/EventsPage.jsx'
import EventDetailPage from './pages/EventDetailPage.jsx'
import BookingsPage from './pages/BookingsPage.jsx'
import BookingDetailPage from './pages/BookingDetailPage.jsx'
import DashboardPage from './pages/DashboardPage.jsx'
import RequireAuth from './auth/RequireAuth.jsx'

function App() {
  return (
    <>
      <div className="fr-atmosphere" aria-hidden="true">
        <span className="fr-orb fr-orb--violet fr-anim-float" />
        <span className="fr-orb fr-orb--pink fr-anim-float-slow" />
        <span className="fr-orb fr-orb--blue fr-anim-float" />
      </div>

      <a className="skip-link" href="#main">
        Skip to main content
      </a>

      {/* Shared across routes: identical on the landing page, and the
          same bar carries the authenticated state on auth pages. */}
      <Navigation />

      <Routes>
        <Route path="/" element={<LandingPage />} />
        <Route path="/events" element={<EventsPage />} />
        <Route path="/events/:eventId" element={<EventDetailPage />} />
        <Route path="/bookings" element={<BookingsPage />} />
        <Route path="/bookings/:bookingId" element={<BookingDetailPage />} />
        <Route
          path="/dashboard"
          element={
            <RequireAuth>
              <DashboardPage />
            </RequireAuth>
          }
        />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  )
}

export default App

