import LandingPage from './pages/LandingPage.jsx'

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

      <LandingPage />
    </>
  )
}

export default App

