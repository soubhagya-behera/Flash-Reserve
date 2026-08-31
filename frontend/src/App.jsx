import './App.css'

function App() {
  return (
    <>
      <div className="fr-atmosphere" aria-hidden="true">
        <span className="fr-orb fr-orb--violet fr-anim-float" />
        <span className="fr-orb fr-orb--pink fr-anim-float-slow" />
        <span className="fr-orb fr-orb--blue fr-anim-float" />
      </div>

      <main className="app-shell">
        <div className="app-welcome fr-anim-fade-up">
          <span className="fr-mark fr-gradient-brand" aria-hidden="true">
            F
          </span>
          <h1 className="fr-display">FlashReserve</h1>
          <p className="fr-subheading">
            Visual foundation in place — pages and features arrive in upcoming
            commits.
          </p>
        </div>
      </main>
    </>
  )
}

export default App

