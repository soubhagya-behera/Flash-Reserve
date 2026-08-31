import { Navigate, Route, Routes } from 'react-router-dom'
import Navigation from './components/navigation/Navigation.jsx'
import LandingPage from './pages/LandingPage.jsx'
import LoginPage from './pages/LoginPage.jsx'
import RegisterPage from './pages/RegisterPage.jsx'

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
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  )
}

export default App

