import Navigation from '../components/navigation/Navigation.jsx'
import Hero from '../components/hero/Hero.jsx'
import CapabilityStrip from '../components/sections/CapabilityStrip.jsx'
import HowItWorks from '../components/sections/HowItWorks.jsx'
import FeatureShowcase from '../components/sections/FeatureShowcase.jsx'
import FinalCta from '../components/sections/FinalCta.jsx'
import Footer from '../components/sections/Footer.jsx'

export default function LandingPage() {
  return (
    <>
      <Navigation />
      <main id="main">
        <Hero />
        <CapabilityStrip />
        <HowItWorks />
        <FeatureShowcase />
        <FinalCta />
      </main>
      <Footer />
    </>
  )
}
