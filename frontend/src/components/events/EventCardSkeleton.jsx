/* Static placeholder mirroring EventCard while real events load. */
export default function EventCardSkeleton() {
  return (
    <div className="event-card event-card--skeleton" aria-hidden="true">
      <div className="event-card__top">
        <span className="event-card__line event-card__line--chip" />
        <span className="event-card__line event-card__line--chip event-card__line--short" />
      </div>
      <span className="event-card__line event-card__line--title" />
      <span className="event-card__line event-card__line--venue" />
      <div className="event-card__foot">
        <span className="event-card__line event-card__line--chip event-card__line--short" />
        <span className="event-card__line event-card__line--chip" />
      </div>
      <span className="event-card__line event-card__line--cta" />
    </div>
  )
}
