import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import Alert from '../components/ui/Alert.jsx'
import Button from '../components/ui/Button.jsx'
import TextField from '../components/ui/TextField.jsx'
import OtpInput from '../components/ui/OtpInput.jsx'
import { toFormErrorState } from '../services/apiClient.js'
import * as authService from '../services/authService.js'
import { validateGmail, validateOtp, validatePassword } from '../utils/validation.js'
import './auth.css'

const COOLDOWN = 60

export default function ForgotPasswordPage() {
  const [step, setStep] = useState(1)
  const [form, setForm] = useState({ email: '', otp: '', password: '', confirm: '', token: '' })
  const [fieldErrors, setFieldErrors] = useState({})
  const [msg, setMsg] = useState(null)
  const [err, setErr] = useState(null)
  const [busy, setBusy] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const ref = useRef(null)

  useEffect(() => {
    if (cooldown <= 0) return
    ref.current = setTimeout(() => setCooldown((c) => c - 1), 1000)
    return () => clearTimeout(ref.current)
  }, [cooldown])

  const setField = (k) => (e) => {
    setForm((c) => ({ ...c, [k]: e.target.value }))
    setFieldErrors((c) => ({ ...c, [k]: null }))
    setErr(null)
  }
  const setOtp = (v) => {
    setForm((c) => ({ ...c, otp: v.replace(/[^0-9]/g, '').slice(0, 6) }))
    setFieldErrors((c) => ({ ...c, otp: null }))
    setErr(null)
  }

  async function sendOtp(e) {
    e.preventDefault()
    const emailErr = validateGmail(form.email)
    setFieldErrors({ email: emailErr })
    if (emailErr) return
    setBusy(true)
    setErr(null)
    setMsg(null)
    try {
      const res = await authService.forgotSendOtp({ email: form.email.trim() })
      setMsg(res.message || `If an account exists for ${form.email.trim().toLowerCase()}, a code has been sent.`)
      setStep(2)
      setCooldown(COOLDOWN)
      setForm((c) => ({ ...c, otp: '' }))
    } catch (error) {
      const s = toFormErrorState(error)
      setErr(s.message); setFieldErrors(s.fieldErrors)
    } finally { setBusy(false) }
  }

  async function verifyOtp(e) {
    e.preventDefault()
    const otpErr = validateOtp(form.otp)
    setFieldErrors((c) => ({ ...c, otp: otpErr }))
    if (otpErr) return
    setBusy(true); setErr(null)
    try {
      const res = await authService.forgotVerifyOtp({ email: form.email.trim(), code: form.otp })
      setForm((c) => ({ ...c, token: res.recoveryToken }))
      setMsg('Email verified. Create your new password.')
      setStep(3)
    } catch (error) {
      const s = toFormErrorState(error)
      setErr(s.message)
      if (s.fieldErrors?.code) setFieldErrors((c) => ({ ...c, otp: s.fieldErrors.code }))
    } finally { setBusy(false) }
  }

  async function resend() {
    if (cooldown > 0 || busy) return
    setBusy(true); setErr(null)
    try {
      await authService.forgotSendOtp({ email: form.email.trim() })
      setCooldown(COOLDOWN); setForm((c) => ({ ...c, otp: '' }))
      setMsg('A new code has been sent.')
    } catch (error) { setErr(toFormErrorState(error).message) }
    finally { setBusy(false) }
  }

  async function resetPwd(e) {
    e.preventDefault()
    const pwdErr = validatePassword(form.password)
    const confErr = !form.confirm ? 'Please confirm password' : form.password !== form.confirm ? 'Passwords do not match' : null
    setFieldErrors({ password: pwdErr, confirm: confErr })
    if (pwdErr || confErr) return
    setBusy(true); setErr(null)
    try {
      await authService.forgotReset({ email: form.email.trim(), recoveryToken: form.token, newPassword: form.password })
      setStep(4)
      setMsg(null)
    } catch (error) {
      const s = toFormErrorState(error)
      setErr(s.message)
      if (s.message.toLowerCase().includes('expired') || s.message.toLowerCase().includes('invalid')) {
        // token expired — back to OTP
      }
    } finally { setBusy(false) }
  }

  return (
    <main id="main" className="auth">
      <div className="fr-container auth__container">
        <div className="auth__card fr-surface-elevated fr-anim-fade-up">
          <Link to="/" className="auth__brand"><span className="fr-mark fr-gradient-brand" aria-hidden="true">F</span>FlashReserve</Link>

          {step < 4 && (
            <div className="auth__steps" aria-hidden="true">
              {[1, 2, 3].map((n) => (
                <span key={n} className={`auth__step${step === n ? ' auth__step--active' : ''}${step > n ? ' auth__step--done' : ''}`}>{step > n ? '✓' : n}</span>
              ))}
            </div>
          )}

          {step === 1 && (
            <>
              <h1 className="fr-heading auth__title">Forgot your password?</h1>
              <p className="fr-small auth__subtitle">Enter your Gmail address and we&apos;ll send you a verification code.</p>
              {err ? <Alert>{err}</Alert> : null}
              {msg ? <Alert tone="success">{msg}</Alert> : null}
              <form className="auth__form" onSubmit={sendOtp} noValidate aria-busy={busy}>
                <TextField id="fp-email" label="Email address" type="email" autoComplete="email" placeholder="you@gmail.com" value={form.email} onChange={setField('email')} error={fieldErrors.email} disabled={busy} />
                <Button type="submit" disabled={busy} className="auth__submit">{busy ? 'Sending…' : 'Send verification code'}</Button>
                <Link to="/login" className="auth__link auth__link--center" style={{ display: 'block', textAlign: 'center', marginTop: '0.25rem' }}>Back to sign in</Link>
              </form>
            </>
          )}

          {step === 2 && (
            <>
              <h1 className="fr-heading auth__title">Verify your email</h1>
              <p className="fr-small auth__subtitle">We sent a code to <strong style={{ color: 'var(--fr-text)' }}>{form.email.trim().toLowerCase()}</strong></p>
              {err ? <Alert>{err}</Alert> : null}
              {msg ? <Alert tone="success">{msg}</Alert> : null}
              <form className="auth__form" onSubmit={verifyOtp} noValidate aria-busy={busy}>
                <div className="fr-field">
                  <label className="fr-field__label">Verification code</label>
                  <OtpInput value={form.otp} onChange={setOtp} disabled={busy} error={fieldErrors.otp} />
                  <p className="fr-small" style={{ marginTop: '0.35rem', color: 'var(--fr-text-muted)' }}>Code expires in 5 minutes.</p>
                </div>
                <Button type="submit" disabled={busy || form.otp.length !== 6} className="auth__submit">{busy ? 'Verifying…' : 'Verify code'}</Button>
                <div className="auth__row">
                  <button type="button" className="auth__link" onClick={() => { setStep(1); setErr(null); setMsg(null) }} disabled={busy}>Change email</button>
                  <button type="button" className="auth__link" onClick={resend} disabled={cooldown > 0 || busy}>{cooldown > 0 ? `Resend in ${cooldown}s` : busy ? 'Sending…' : 'Resend code'}</button>
                </div>
              </form>
            </>
          )}

          {step === 3 && (
            <>
              <h1 className="fr-heading auth__title">Create a new password</h1>
              <p className="fr-small auth__subtitle">Choose a strong password to secure your account.</p>
              {err ? <Alert>{err}</Alert> : null}
              {msg ? <Alert tone="success">{msg}</Alert> : null}
              <form className="auth__form" onSubmit={resetPwd} noValidate aria-busy={busy}>
                <TextField id="fp-pwd" label="New password" type="password" autoComplete="new-password" placeholder="At least 8 characters" value={form.password} onChange={setField('password')} error={fieldErrors.password} disabled={busy} />
                <TextField id="fp-confirm" label="Confirm password" type="password" autoComplete="new-password" placeholder="Repeat password" value={form.confirm} onChange={setField('confirm')} error={fieldErrors.confirm} disabled={busy} />
                <Button type="submit" disabled={busy} className="auth__submit">{busy ? 'Resetting…' : 'Reset password'}</Button>
                <button type="button" className="auth__link auth__link--center" onClick={() => setStep(2)} disabled={busy}>Back to verification</button>
              </form>
            </>
          )}

          {step === 4 && (
            <>
              <div style={{ textAlign: 'center', padding: '0.5rem 0' }}>
                <div style={{ width: '3rem', height: '3rem', borderRadius: '999px', background: 'var(--fr-success-soft)', display: 'inline-grid', placeItems: 'center', color: 'var(--fr-success)', fontSize: '1.5rem' }}>✓</div>
              </div>
              <h1 className="fr-heading auth__title" style={{ textAlign: 'center' }}>Password reset successfully</h1>
              <p className="fr-small auth__subtitle" style={{ textAlign: 'center' }}>Your password has been updated securely. You can now sign in with your new password.</p>
              <Link to="/login" className="fr-btn fr-btn--primary auth__submit" style={{ textAlign: 'center' }}>Back to sign in</Link>
            </>
          )}
        </div>
      </div>
    </main>
  )
}
