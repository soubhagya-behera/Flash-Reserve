/* ============================================================
   FlashReserve — Landing page presentation data
   STATIC CONTENT ONLY. Nothing here is live: the seat map,
   availability counts and hold timer are illustrative props for
   the hero visual. Real data arrives when the backend is wired.
   ============================================================ */

export const navLinks = [
  { label: 'Events', href: '#' },
  { label: 'How it works', href: '#how-it-works' },
  { label: 'Features', href: '#features' },
]

export const heroFlow = ['Event', 'Seat', 'Reservation', 'Confirmation']

/* Illustrative seat map for the hero ticket. States drive styling:
   available → filled, taken → muted, selected → accent highlight. */
export const seatRows = [
  { label: 'A', seats: ['taken', 'available', 'available', 'selected', 'available', 'available'] },
  { label: 'B', seats: ['available', 'available', 'taken', 'available', 'taken', 'available'] },
  { label: 'C', seats: ['available', 'taken', 'available', 'available', 'available', 'taken'] },
]

export const seatStates = [
  { state: 'available', label: 'Available' },
  { state: 'selected', label: 'Held for you' },
  { state: 'taken', label: 'Reserved' },
]

export const capabilities = [
  {
    title: 'Distributed seat locking',
    detail: 'One owner per seat, enforced across every request.',
  },
  {
    title: 'Real-time-safe reservation',
    detail: 'Transactional writes keep inventory consistent.',
  },
  {
    title: 'Secure payments',
    detail: 'Sandboxed checkout, wired for test mode.',
  },
  {
    title: 'Automatic hold expiration',
    detail: 'Abandoned holds release seats back on their own.',
  },
]

export const steps = [
  {
    number: '01',
    title: 'Choose an event',
    detail: 'Browse what is playing and when, at a glance.',
  },
  {
    number: '02',
    title: 'Select your seat',
    detail: 'See live availability and pick the seat you want.',
  },
  {
    number: '03',
    title: 'Reserve your seat',
    detail: 'Your seat is locked to you while you decide.',
  },
  {
    number: '04',
    title: 'Confirm your booking',
    detail: 'Pay securely and get your confirmed ticket.',
  },
]

export const features = [
  {
    title: 'Distributed seat locking',
    detail:
      'A shared lock guarantees that a seat can only ever be held by one person — no matter how many requests arrive at once.',
  },
  {
    title: 'PostgreSQL optimistic locking',
    detail:
      'Reservation writes run inside transactions with optimistic checks, so seat inventory stays accurate under load.',
  },
  {
    title: 'Redis rate limiting',
    detail:
      'Flash traffic is shaped at the edge of the reservation flow before it can overwhelm anything downstream.',
  },
  {
    title: 'Automatic hold expiration',
    detail:
      'Unconfirmed holds count down and quietly return seats to inventory — no stuck inventory, no manual cleanup.',
  },
  {
    title: 'Secure JWT authentication',
    detail:
      'Every reservation belongs to a verified account, backed by signed tokens on protected routes.',
  },
  {
    title: 'Razorpay payments, test mode',
    detail:
      'The full checkout journey runs end to end inside Razorpay\u2019s sandbox — safe to explore, ready to go live.',
  },
]

export const footerLinks = [
  { label: 'Events', href: '#' },
  { label: 'How it works', href: '#how-it-works' },
  { label: 'Features', href: '#features' },
  {
    label: 'GitHub',
    href: 'https://github.com/soubhagya-behera/Flash-Reserve',
  },
]
