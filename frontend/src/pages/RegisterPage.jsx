import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import Alert from '../components/ui/Alert.jsx'
import Button from '../components/ui/Button.jsx'
import TextField from '../components/ui/TextField.jsx'
import { useAuth } from '../auth/authContext.js'
import { toFormErrorState } from '../services/apiClient.js'
import { validateEmail, validateName, validatePassword } from '../utils/validation.js'
import './auth.css'

export default function RegisterPage() {
  const { user, register } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState({ name: '', email: '', password: '' })
  const [fieldErrors, setFieldErrors] = useState({})
  const [formError, setFormError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  // Already signed in — registration has nothing to offer.
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
      name: validateName(form.name),
      email: validateEmail(form.email),
      password: validatePassword(form.password),
    }
    setFieldErrors(nextErrors)
    if (nextErrors.name || nextErrors.email || nextErrors.password) return

    setSubmitting(true)
    try {
      // The backend returns a JWT immediately on registration, so the
      // new account is signed in without a separate login step.
      await register({
        name: form.name.trim(),
        email: form.email.trim(),
        password: form.password,
      })
      navigate('/', { replace: true })
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

          <h1 className="fr-heading auth__title">Create your account</h1>
          <p className="fr-small auth__subtitle">
            One account for every reservation — your seats, always yours.
          </p>

          {formError ? <Alert>{formError}</Alert> : null}

          <form className="auth__form" onSubmit={handleSubmit} noValidate aria-busy={submitting}>
            <TextField
              id="register-name"
              label="Full name"
              type="text"
              autoComplete="name"
              placeholder="Alex Morgan"
              maxLength={100}
              value={form.name}
              onChange={setField('name')}
              error={fieldErrors.name}
              disabled={submitting}
            />
            <TextField
              id="register-email"
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
              id="register-password"
              label="Password"
              type="password"
              autoComplete="new-password"
              placeholder="At least 8 characters"
              value={form.password}
              onChange={setField('password')}
              error={fieldErrors.password}
              disabled={submitting}
            />
            <Button type="submit" disabled={submitting} className="auth__submit">
              {submitting ? 'Creating account…' : 'Create account'}
            </Button>
          </form>

          <p className="fr-small auth__switch">
            Already have an account?{' '}
            <Link to="/login" className="auth__switch-link">
              Sign in
            </Link>
          </p>
        </div>
      </div>
    </main>
  )
}
