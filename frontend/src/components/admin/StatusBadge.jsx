import './admin-widgets.css'

/* Tone per status. Text always travels with the color so status is
   never communicated by color alone. */
const TONES = {
  DRAFT: 'draft',
  PUBLISHED: 'published',
  CANCELLED: 'cancelled',
  COMPLETED: 'completed',
}

export default function StatusBadge({ status }) {
  const tone = TONES[status] ?? 'draft'
  return (
    <span className={`admin-badge admin-badge--${tone}`}>
      <span className="admin-badge__dot" aria-hidden="true" />
      {status}
    </span>
  )
}