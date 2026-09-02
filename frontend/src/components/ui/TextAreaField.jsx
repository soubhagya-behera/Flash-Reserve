import './text-field.css'

/**
 * Multi-line sibling of TextField, reusing the exact same
 * fr-field classes so forms stay visually identical.
 * `error` is a message string, or null/undefined when valid.
 */
export default function TextAreaField({
  id,
  label,
  value,
  onChange,
  error = null,
  disabled = false,
  rows = 4,
  ...rest
}) {
  const errorId = error ? `${id}-error` : undefined

  return (
    <div className="fr-field">
      <label className="fr-field__label" htmlFor={id}>
        {label}
      </label>
      <textarea
        id={id}
        className={`fr-field__input fr-field__input--area${error ? ' fr-field__input--invalid' : ''}`}
        rows={rows}
        value={value}
        onChange={onChange}
        disabled={disabled}
        aria-invalid={error ? true : undefined}
        aria-describedby={errorId}
        {...rest}
      />
      {error ? (
        <p className="fr-field__message" id={errorId}>
          {error}
        </p>
      ) : null}
    </div>
  )
}