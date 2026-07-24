"use client";

import { motion } from "motion/react";
import Link from "next/link";
import { ReactNode } from "react";

type Variant = "primary" | "ghost-light" | "ghost-dark";
type Size = "md" | "lg";

const variantClasses: Record<Variant, string> = {
  primary:
    "bg-orange-500 text-navy-900 shadow-[0_8px_30px_-8px_rgba(250,153,35,0.6)] hover:bg-orange-400",
  "ghost-light":
    "border border-navy-900/15 bg-white text-navy-900 hover:bg-navy-50",
  "ghost-dark":
    "border border-white/20 bg-white/5 text-white backdrop-blur hover:bg-white/10",
};

const sizeClasses: Record<Size, string> = {
  md: "h-11 px-5 text-sm",
  lg: "h-14 px-7 text-base",
};

export function Button({
  children,
  href,
  variant = "primary",
  size = "md",
  className = "",
  onClick,
  external,
  download,
}: {
  children: ReactNode;
  href?: string;
  variant?: Variant;
  size?: Size;
  className?: string;
  onClick?: () => void;
  external?: boolean;
  download?: boolean | string;
}) {
  const classes = `inline-flex items-center justify-center gap-2 rounded-full font-semibold tracking-tight transition-colors ${variantClasses[variant]} ${sizeClasses[size]} ${className}`;

  const content = (
    <motion.span
      whileHover={{ scale: 1.03 }}
      whileTap={{ scale: 0.96 }}
      transition={{ type: "spring", stiffness: 400, damping: 20 }}
      className={classes}
    >
      {children}
    </motion.span>
  );

  if (href && download) {
    // A real static-file download needs a plain anchor with `download` —
    // next/link's client-side routing isn't meant for non-page assets.
    return (
      <a
        href={href}
        download={download}
        onClick={onClick}
        className="inline-block"
      >
        {content}
      </a>
    );
  }

  if (href) {
    return (
      <Link
        href={href}
        onClick={onClick}
        target={external ? "_blank" : undefined}
        rel={external ? "noopener noreferrer" : undefined}
        className="inline-block"
      >
        {content}
      </Link>
    );
  }

  return (
    <button onClick={onClick} className="inline-block cursor-pointer">
      {content}
    </button>
  );
}
