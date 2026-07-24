export function GradientMesh({ className = "" }: { className?: string }) {
  return (
    <div className={`pointer-events-none absolute inset-0 overflow-hidden ${className}`}>
      <div className="absolute inset-0 bg-navy-950" />
      <div
        className="absolute -top-1/3 -right-1/4 h-[70%] w-[70%] rounded-full opacity-40 blur-3xl animate-float"
        style={{
          background:
            "radial-gradient(circle, var(--color-orange-500) 0%, transparent 70%)",
        }}
      />
      <div
        className="absolute top-1/3 -left-1/4 h-[60%] w-[60%] rounded-full opacity-30 blur-3xl animate-float"
        style={{
          background:
            "radial-gradient(circle, var(--color-navy-600) 0%, transparent 70%)",
          animationDelay: "1.5s",
        }}
      />
      <div className="absolute inset-0 bg-grain" />
      <div className="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-navy-950" />
    </div>
  );
}
