import Hero from '../components/hero/Hero.jsx'
import CapabilityStrip from '../components/sections/CapabilityStrip.jsx'
import HowItWorks from '../components/sections/HowItWorks.jsx'
import FeatureShowcase from '../components/sections/FeatureShowcase.jsx'
import FinalCta from '../components/sections/FinalCta.jsx'
import Footer from '../components/sections/Footer.jsx'

/* The Navigation bar is rendered by App so it is shared with the
   auth pages; this page owns only its own content. */
export default function LandingPage() {
  return (
    <main id="main">
        <Hero />
        <CapabilityStrip />
        <HowItWorks />
        <FeatureShowcase />
        <FinalCta />
      <Footer />
    </main>
  )
}
