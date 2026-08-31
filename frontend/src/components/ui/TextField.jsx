import './text-field.css'

/**
 * Labeled input with accessible error wiring:
 * label↔input association, aria-invalid and aria-describedby.
 * `error` is a message string, or null/undefined when valid.
 */
export default function TextField({
  id,
  label,
  type = 'text',
  value,
  onChange,
  error = null,
  disabled = false,
  autoComplete,
  ...rest
}) {
  const errorId = error ? `${id}-error` : undefined

  return (
    <div className="fr-field">
      <label className="fr-field__label" htmlFor={id}>
        {label}
      </label>
      <input
        id={id}
        className={`fr-field__input${error ? ' fr-field__input--invalid' : ''}`}
        type={type}
        value={value}
        onChange={onChange}
        disabled={disabled}
        autoComplete={autoComplete}
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
