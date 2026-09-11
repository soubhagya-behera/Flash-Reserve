import { useEffect, useRef, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import Alert from '../components/ui/Alert.jsx'
import Button from '../components/ui/Button.jsx'
import TextField from '../components/ui/TextField.jsx'
import OtpInput from '../components/ui/OtpInput.jsx'
import { useAuth } from '../auth/authContext.js'
import { toFormErrorState } from '../services/apiClient.js'
import * as authService from '../services/authService.js'
import { validateGmail, validateName, validateOtp, validatePassword } from '../utils/validation.js'
import './auth.css'

const RESEND_COOLDOWN = 60

export default function RegisterPage() {
  const { user, register } = useAuth()
  const navigate = useNavigate()

  const [step, setStep] = useState(1)
  const [form, setForm] = useState({ name: '', email: '', otp: '', password: '', confirm: '' })
  const [fieldErrors, setFieldErrors] = useState({})
  const [formError, setFormError] = useState(null)
  const [formSuccess, setFormSuccess] = useState(null)
  const [sending, setSending] = useState(false)
  const [verifying, setVerifying] = useState(false)
  const [creating, setCreating] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const [otpVerified, setOtpVerified] = useState(false)
  const cooldownRef = useRef(null)

  useEffect(() => {
    if (cooldown <= 0) return
    cooldownRef.current = setTimeout(() => setCooldown((c) => c - 1), 1000)
    return () => clearTimeout(cooldownRef.current)
  }, [cooldown])

  if (user) return <Navigate to="/" replace />

  const setField = (field) => (event) => {
    setForm((c) => ({ ...c, [field]: event.target.value }))
    setFieldErrors((c) => ({ ...c, [field]: null }))
    setFormError(null)
  }

  const setOtp = (val) => {
    setForm((c) => ({ ...c, otp: val.replace(/[^0-9]/g, '').slice(0, 6) }))
    setFieldErrors((c) => ({ ...c, otp: null }))
    setFormError(null)
  }

  async function handleSendOtp(event) {
    event.preventDefault()
    const nameError = validateName(form.name)
    const emailError = validateGmail(form.email)
    setFieldErrors({ name: nameError, email: emailError })
    if (nameError || emailError) return
    setSending(true)
    setFormError(null)
    setFormSuccess(null)
    try {
      await authService.sendRegisterOtp({ name: form.name.trim(), email: form.email.trim() })
      setStep(2)
      setOtpVerified(false)
      setForm((c) => ({ ...c, otp: '' }))
      setCooldown(RESEND_COOLDOWN)
      setFormSuccess(`We sent a 6-digit code to ${form.email.trim().toLowerCase()}`)
    } catch (error) {
      const state = toFormErrorState(error)
      setFormError(state.message)
      setFieldErrors(state.fieldErrors)
    } finally {
      setSending(false)
    }
  }

  async function handleVerifyOtp(event) {
    event.preventDefault()
    const otpError = validateOtp(form.otp)
    setFieldErrors((c) => ({ ...c, otp: otpError }))
    if (otpError) return
    setVerifying(true)
    setFormError(null)
    try {
      await authService.verifyRegisterOtp({ email: form.email.trim(), code: form.otp })
      setOtpVerified(true)
      setFormSuccess('Email verified. Set your password to create your account.')
      setStep(3)
    } catch (error) {
      const state = toFormErrorState(error)
      setFormError(state.message)
      if (state.fieldErrors?.code) setFieldErrors((c) => ({ ...c, otp: state.fieldErrors.code }))
    } finally {
      setVerifying(false)
    }
  }

  async function handleResend() {
    if (cooldown > 0 || sending) return
    setSending(true)
    setFormError(null)
    try {
      await authService.sendRegisterOtp({ name: form.name.trim(), email: form.email.trim() })
      setForm((c) => ({ ...c, otp: '' }))
      setCooldown(RESEND_COOLDOWN)
      setFormSuccess(`We sent a new code to ${form.email.trim().toLowerCase()}`)
    } catch (error) {
      const state = toFormErrorState(error)
      setFormError(state.message)
    } finally {
      setSending(false)
    }
  }

  async function handleCreateAccount(event) {
    event.preventDefault()
    const pwdError = validatePassword(form.password)
    const confirmError = !form.confirm
      ? 'Please confirm your password'
      : form.password !== form.confirm
        ? 'Passwords do not match'
        : null
    setFieldErrors({ password: pwdError, confirm: confirmError })
    if (pwdError || confirmError) return
    if (!otpVerified) {
      setFormError('Please verify your email first.')
      setStep(2)
      return
    }
    setCreating(true)
    setFormError(null)
    try {
      await register({ name: form.name.trim(), email: form.email.trim(), password: form.password })
      navigate('/', { replace: true })
    } catch (error) {
      const state = toFormErrorState(error)
      if (state.message.toLowerCase().includes('not verified')) {
        setOtpVerified(false)
        setStep(2)
      }
      setFormError(state.message)
      setFieldErrors((prev) => ({ ...prev, ...state.fieldErrors }))
    } finally {
      setCreating(false)
    }
  }

  const busy = sending || verifying || creating

  return (
    <main id="main" className="auth">
      <div className="fr-container auth__container">
        <div className="auth__card fr-surface-elevated fr-anim-fade-up">
          <Link to="/" className="auth__brand">
            <span className="fr-mark fr-gradient-brand" aria-hidden="true">F</span>
            FlashReserve
          </Link>

          <div className="auth__steps" aria-hidden="true">
            {[1, 2, 3].map((n) => (
              <span key={n} className={`auth__step${step === n ? ' auth__step--active' : ''}${step > n ? ' auth__step--done' : ''}`}>
                {step > n ? '✓' : n}
              </span>
            ))}
          </div>

          {step === 1 && (
            <>
              <h1 className="fr-heading auth__title">Create your account</h1>
              <p className="fr-small auth__subtitle">Gmail verification required — fast, secure signup.</p>
              {formError ? <Alert>{formError}</Alert> : null}
              {formSuccess ? <Alert tone="success">{formSuccess}</Alert> : null}
              <form className="auth__form" onSubmit={handleSendOtp} noValidate aria-busy={sending}>
                <TextField id="register-name" label="Full name" type="text" autoComplete="name" placeholder="Alex Morgan" maxLength={100} value={form.name} onChange={setField('name')} error={fieldErrors.name} disabled={sending} />
                <TextField id="register-email" label="Email address" type="email" autoComplete="email" placeholder="you@gmail.com" value={form.email} onChange={setField('email')} error={fieldErrors.email} disabled={sending} />
                <Button type="submit" disabled={sending} className="auth__submit">{sending ? 'Sending code…' : 'Verify email'}</Button>
              </form>
            </>
          )}

          {step === 2 && (
            <>
              <h1 className="fr-heading auth__title">Verify your email</h1>
              <p className="fr-small auth__subtitle">We sent a 6-digit code to <strong style={{ color: 'var(--fr-text)' }}>{form.email.trim().toLowerCase()}</strong></p>
              {formError ? <Alert>{formError}</Alert> : null}
              {formSuccess ? <Alert tone="success">{formSuccess}</Alert> : null}
              <form className="auth__form" onSubmit={handleVerifyOtp} noValidate aria-busy={verifying}>
                <div className="fr-field">
                  <label className="fr-field__label" htmlFor="register-otp-0">Verification code</label>
                  <OtpInput value={form.otp} onChange={setOtp} disabled={verifying || sending} error={fieldErrors.otp} />
                  <p className="fr-small" style={{ marginTop: '0.35rem', color: 'var(--fr-text-muted)' }}>Code expires in 5 minutes.</p>
                </div>
                <Button type="submit" disabled={verifying || form.otp.length !== 6} className="auth__submit">{verifying ? 'Verifying…' : 'Verify OTP'}</Button>
                <div className="auth__row">
                  <button type="button" className="auth__link" onClick={() => { setStep(1); setFormError(null); setFormSuccess(null) }} disabled={busy}>Change email</button>
                  <button type="button" className="auth__link" onClick={handleResend} disabled={cooldown > 0 || sending || verifying}>
                    {cooldown > 0 ? `Resend in ${cooldown}s` : sending ? 'Sending…' : 'Resend code'}
                  </button>
                </div>
              </form>
            </>
          )}

          {step === 3 && (
            <>
              <h1 className="fr-heading auth__title">Set your password</h1>
              <p className="fr-small auth__subtitle">Email verified ✓ — choose a strong password to finish.</p>
              {formError ? <Alert>{formError}</Alert> : null}
              {formSuccess ? <Alert tone="success">{formSuccess}</Alert> : null}
              <form className="auth__form" onSubmit={handleCreateAccount} noValidate aria-busy={creating}>
                <TextField id="register-password" label="New password" type="password" autoComplete="new-password" placeholder="At least 8 characters" value={form.password} onChange={setField('password')} error={fieldErrors.password} disabled={creating} />
                <TextField id="register-confirm" label="Confirm password" type="password" autoComplete="new-password" placeholder="Repeat password" value={form.confirm} onChange={setField('confirm')} error={fieldErrors.confirm} disabled={creating} />
                <Button type="submit" disabled={creating} className="auth__submit">{creating ? 'Creating account…' : 'Create account'}</Button>
                <button type="button" className="auth__link auth__link--center" onClick={() => setStep(2)} disabled={creating}>Back to verification</button>
              </form>
            </>
          )}

          <p className="fr-small auth__switch">Already have an account? <Link to="/login" className="auth__switch-link">Sign in</Link></p>
        </div>
      </div>
    </main>
  )
}
