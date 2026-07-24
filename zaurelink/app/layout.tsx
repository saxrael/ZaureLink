import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

const title = "ZaureLink — Offline Hausa ↔ English Speech Translator";
const description =
  "ZaureLink translates Hausa and English live, both directions, entirely on your phone — no signal, no server, no data plan. Powered by Gemma 4 E2B running fully on-device.";

export const metadata: Metadata = {
  metadataBase: new URL("https://zaurelink.app"),
  title,
  description,
  keywords: [
    "ZaureLink",
    "Hausa translator",
    "offline translation",
    "speech to speech",
    "Gemma 4",
    "on-device AI",
    "ABU Zaria",
  ],
  openGraph: {
    title,
    description,
    type: "website",
    images: ["/zaurelink-logo.png"],
  },
  twitter: {
    card: "summary_large_image",
    title,
    description,
    images: ["/zaurelink-logo.png"],
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col bg-white text-navy-900">
        {children}
      </body>
    </html>
  );
}
