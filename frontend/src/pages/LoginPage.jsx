import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import Alert from '../components/ui/Alert.jsx'
import Button from '../components/ui/Button.jsx'
import TextField from '../components/ui/TextField.jsx'
import { useAuth } from '../auth/authContext.js'
import { toFormErrorState } from '../services/apiClient.js'
import { validateEmail, validateRequired } from '../utils/validation.js'
import './auth.css'

export default function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  // Pages that require an account (e.g. reserving a seat) pass their
  // location in router state so sign-in returns the user to them.
  const from = useLocation().state?.from ?? '/'
  const [form, setForm] = useState({ email: '', password: '' })
  const [fieldErrors, setFieldErrors] = useState({})
  const [formError, setFormError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  // Already signed in — the login form has nothing to offer.
  if (user) {
    return <Navigate to="/" replace />
  }

  const setField = (field) => (event) => {
    const value = event.target.value
    setForm((current) => ({ ...current, [field]: value }))
    setFieldErrors((current) => ({ ...current, [field]: null }))
  }

  async function handleSubmit(event) {
    event.preventDefault()
    setFormError(null)

    const nextErrors = {
      email: validateEmail(form.email),
      password: validateRequired(form.password, 'Password'),
    }
    setFieldErrors(nextErrors)
    if (nextErrors.email || nextErrors.password) return

    setSubmitting(true)
    try {
      await login({ email: form.email.trim(), password: form.password })
      navigate(from, { replace: true })
    } catch (error) {
      const state = toFormErrorState(error)
      setFormError(state.message)
      setFieldErrors(state.fieldErrors)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main id="main" className="auth">
      <div className="fr-container auth__container">
        <div className="auth__card fr-surface-elevated fr-anim-fade-up">
          <Link to="/" className="auth__brand">
            <span className="fr-mark fr-gradient-brand" aria-hidden="true">
              F
            </span>
            FlashReserve
          </Link>

          <h1 className="fr-heading auth__title">Welcome back</h1>
          <p className="fr-small auth__subtitle">
            Sign in to reserve your seat for the moments you don&apos;t want
            to miss.
          </p>

          {formError ? <Alert>{formError}</Alert> : null}

          <form className="auth__form" onSubmit={handleSubmit} noValidate aria-busy={submitting}>
            <TextField
              id="login-email"
              label="Email"
              type="email"
              autoComplete="email"
              placeholder="you@example.com"
              value={form.email}
              onChange={setField('email')}
              error={fieldErrors.email}
              disabled={submitting}
            />
            <TextField
              id="login-password"
              label="Password"
              type="password"
              autoComplete="current-password"
              placeholder="Your password"
              value={form.password}
              onChange={setField('password')}
              error={fieldErrors.password}
              disabled={submitting}
            />
            <Button type="submit" disabled={submitting} className="auth__submit">
              {submitting ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>

          <p className="fr-small auth__switch">
            New to FlashReserve?{' '}
            <Link to="/register" className="auth__switch-link">
              Create an account
            </Link>
          </p>
        </div>
      </div>
    </main>
  )
}
