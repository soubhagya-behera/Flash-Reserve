import { useRef } from 'react'

export default function OtpInput({ value, onChange, disabled, error }) {
  const inputsRef = useRef([])

  const digits = value.padEnd(6, '').split('').slice(0, 6)

  function setDigit(index, char) {
    const next = digits.slice()
    next[index] = char
    onChange(next.join('').replace(/ /g, ''))
  }

  function handleChange(index, e) {
    const v = e.target.value.replace(/[^0-9]/g, '').slice(-1)
    setDigit(index, v)
    if (v && index < 5) inputsRef.current[index + 1]?.focus()
  }

  function handleKeyDown(index, e) {
    if (e.key === 'Backspace' && !digits[index] && index > 0) {
      inputsRef.current[index - 1]?.focus()
    }
    if (e.key === 'ArrowLeft' && index > 0) inputsRef.current[index - 1]?.focus()
    if (e.key === 'ArrowRight' && index < 5) inputsRef.current[index + 1]?.focus()
  }

  function handlePaste(e) {
    e.preventDefault()
    const pasted = e.clipboardData.getData('text').replace(/[^0-9]/g, '').slice(0, 6)
    if (pasted) {
      onChange(pasted.padEnd(6, '').slice(0, 6).trim())
      const nextIdx = Math.min(pasted.length, 5)
      inputsRef.current[nextIdx]?.focus()
    }
  }

  return (
    <div>
      <div className="fr-otp">
        {Array.from({ length: 6 }).map((_, i) => (
          <input
            key={i}
            ref={(el) => {
              inputsRef.current[i] = el
            }}
            className={`fr-otp__input${error ? ' fr-otp__input--invalid' : ''}`}
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={1}
            value={digits[i]?.trim() ?? ''}
            onChange={(e) => handleChange(i, e)}
            onKeyDown={(e) => handleKeyDown(i, e)}
            onPaste={handlePaste}
            disabled={disabled}
            aria-label={`Digit ${i + 1} of 6`}
          />
        ))}
      </div>
      {error ? <p className="fr-field__message" style={{ marginTop: '0.4rem' }}>{error}</p> : null}
    </div>
  )
}
