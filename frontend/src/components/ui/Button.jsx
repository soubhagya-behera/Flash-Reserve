import './button.css'

/**
 * Button primitive. Renders an <a> when `href` is given so
 * in-page anchors stay real links, otherwise a <button>.
 * During the landing phase most actions are visual placeholders.
 */
export default function Button({
  href,
  variant = 'primary',
  className = '',
  children,
  ...rest
}) {
  const classes = ['fr-btn', `fr-btn--${variant}`, className]
    .filter(Boolean)
    .join(' ')

  if (href) {
    return (
      <a className={classes} href={href} {...rest}>
        {children}
      </a>
    )
  }

  return (
    <button type="button" className={classes} {...rest}>
      {children}
    </button>
  )
}
