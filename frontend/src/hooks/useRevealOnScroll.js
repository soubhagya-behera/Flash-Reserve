import { useEffect, useRef } from 'react'

/**
 * Adds the `is-visible` class once the element scrolls into view.
 * Pair with the `.fr-reveal` animation primitive; the observer
 * disconnects after the first reveal so it costs nothing afterwards.
 */
export default function useRevealOnScroll() {
  const ref = useRef(null)

  useEffect(() => {
    const node = ref.current
    if (!node) return undefined

    if (typeof IntersectionObserver === 'undefined') {
      node.classList.add('is-visible')
      return undefined
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (!entry.isIntersecting) return
          entry.target.classList.add('is-visible')
          observer.unobserve(entry.target)
        })
      },
      { threshold: 0.15, rootMargin: '0px 0px -8% 0px' },
    )

    observer.observe(node)
    return () => observer.disconnect()
  }, [])

  return ref
}
