import { Navbar } from "./components/Navbar";
import { Hero } from "./components/Hero";
import { TrustBar } from "./components/TrustBar";
import { Problem } from "./components/Problem";
import { Marquee } from "./components/Marquee";
import { Features } from "./components/Features";
import { TechShowcase } from "./components/TechShowcase";
import { HowItWorks } from "./components/HowItWorks";
import { Demo } from "./components/Demo";
import { Download } from "./components/Download";
import { Footer } from "./components/Footer";

export default function Home() {
  return (
    <>
      <Navbar />
      <main className="flex-1">
        <Hero />
        <TrustBar />
        <Problem />
        <Marquee />
        <Features />
        <TechShowcase />
        <HowItWorks />
        <Demo />
        <Download />
      </main>
      <Footer />
    </>
  );
}
