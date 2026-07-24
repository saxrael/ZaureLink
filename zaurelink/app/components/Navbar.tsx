import Image from "next/image";
import { Button } from "./Button";
import { Container } from "./Container";

const LINKS = [
  { href: "#features", label: "Features" },
  { href: "#how-it-works", label: "How it works" },
  { href: "#tech", label: "Tech" },
];

export function Navbar() {
  return (
    <header className="fixed inset-x-0 top-0 z-50 border-b border-white/10 bg-navy-950/70 backdrop-blur-lg">
      <Container className="flex h-16 items-center justify-between">
        <a href="#top" className="flex items-center gap-2.5">
          <Image
            src="/zaurelink-icon.png"
            alt="ZaureLink"
            width={32}
            height={32}
            className="rounded-[8px]"
            priority
          />
          <span className="text-lg font-extrabold tracking-tight text-white">
            ZaureLink
          </span>
        </a>

        <nav className="hidden items-center gap-8 md:flex">
          {LINKS.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="text-sm font-medium text-white/70 transition-colors hover:text-white"
            >
              {link.label}
            </a>
          ))}
        </nav>

        <Button href="#download" size="md" variant="primary">
          Download
        </Button>
      </Container>
    </header>
  );
}
