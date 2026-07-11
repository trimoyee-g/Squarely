import type { ReactNode } from "react";

export const money = (n: number, ccy = "INR") =>
  new Intl.NumberFormat("en-IN", { style: "currency", currency: ccy }).format(n);

export function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <div className={`rounded-xl border border-white/10 bg-white/5 p-4 ${className}`}>{children}</div>;
}

export function Button({ children, onClick, type = "button", variant = "primary", disabled }: {
  children: ReactNode; onClick?: () => void; type?: "button" | "submit";
  variant?: "primary" | "ghost" | "danger"; disabled?: boolean;
}) {
  const styles = {
    primary: "bg-indigo-400 text-indigo-950 hover:bg-indigo-300",
    ghost: "bg-white/10 text-slate-200 hover:bg-white/20",
    danger: "bg-rose-500 text-white hover:bg-rose-400",
  }[variant];
  return (
    <button type={type} onClick={onClick} disabled={disabled}
      className={`rounded-lg px-3 py-1.5 text-sm font-medium transition disabled:opacity-50 ${styles}`}>
      {children}
    </button>
  );
}

export function Badge({ children, tone = "slate" }: { children: ReactNode; tone?: string }) {
  const tones: Record<string, string> = {
    slate: "bg-white/10 text-slate-300",
    green: "bg-emerald-400/20 text-emerald-300",
    amber: "bg-amber-400/20 text-amber-300",
    red: "bg-rose-400/20 text-rose-300",
    blue: "bg-blue-400/20 text-blue-300",
  };
  return <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${tones[tone] || tones.slate}`}>{children}</span>;
}

export const statusTone: Record<string, string> = {
  PENDING: "slate", PAYMENT_CLAIMED: "amber", SETTLED: "green", DISPUTED: "red",
  UPCOMING: "blue", DUE: "amber", OVERDUE: "red",
};

const AVATAR_TONES = [
  "bg-indigo-400/20 text-indigo-300",
  "bg-violet-400/20 text-violet-300",
  "bg-amber-400/20 text-amber-300",
  "bg-emerald-400/20 text-emerald-300",
  "bg-rose-400/20 text-rose-300",
  "bg-sky-400/20 text-sky-300",
  "bg-teal-400/20 text-teal-300",
  "bg-fuchsia-400/20 text-fuchsia-300",
];

export function avatarTone(id: number) {
  return AVATAR_TONES[Math.abs(id) % AVATAR_TONES.length];
}

export function initials(name?: string, id?: number) {
  if (!name) return id !== undefined ? `#${id}` : "?";
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return "?";
  return (parts[0][0] + (parts[1]?.[0] ?? "")).toUpperCase();
}

const AVATAR_SIZES = { xs: "h-6 w-6 text-[10px]", sm: "h-7 w-7 text-xs", md: "h-9 w-9 text-sm", lg: "h-11 w-11 text-sm" };

export function Avatar({ id, name, size = "md", ring = false }: {
  id: number; name?: string; size?: keyof typeof AVATAR_SIZES; ring?: boolean;
}) {
  return (
    <div
      title={name ?? `User #${id}`}
      className={`flex flex-shrink-0 items-center justify-center rounded-full font-medium ${AVATAR_SIZES[size]} ${avatarTone(id)} ${ring ? "ring-2 ring-[#14122a]" : ""}`}
    >
      {initials(name, id)}
    </div>
  );
}

export function AvatarStack({ users, max = 3, size = "xs" }: {
  users: { id: number; name?: string }[]; max?: number; size?: keyof typeof AVATAR_SIZES;
}) {
  const shown = users.slice(0, max);
  const extra = users.length - shown.length;
  return (
    <div className="flex">
      {shown.map((u) => (
        <div key={u.id} className="-ml-2 first:ml-0">
          <Avatar id={u.id} name={u.name} size={size} ring />
        </div>
      ))}
      {extra > 0 && (
        <div className={`-ml-2 flex flex-shrink-0 items-center justify-center rounded-full bg-white/10 font-medium text-slate-300 ring-2 ring-[#14122a] ${AVATAR_SIZES[size]}`}>
          +{extra}
        </div>
      )}
    </div>
  );
}

export function Tabs<T extends string>({ tabs, active, onChange }: {
  tabs: { key: T; label: string }[]; active: T; onChange: (key: T) => void;
}) {
  return (
    <div className="flex gap-1 border-b border-white/10">
      {tabs.map((t) => (
        <button key={t.key} onClick={() => onChange(t.key)}
          className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium transition ${
            active === t.key ? "border-indigo-400 text-indigo-300" : "border-transparent text-slate-400 hover:text-slate-200"}`}>
          {t.label}
        </button>
      ))}
    </div>
  );
}

export function SettlementStepper({ status }: { status: string }) {
  const disputed = status === "DISPUTED";
  const current = status === "PENDING" ? 0 : status === "PAYMENT_CLAIMED" || disputed ? 1 : 2;
  const steps = ["Pending", disputed ? "Disputed" : "Claimed", "Confirmed"];

  return (
    <div className="flex items-center">
      {steps.map((label, i) => (
        <div key={label} className="flex flex-1 items-center">
          {i > 0 && (
            <div className={`h-0.5 flex-1 ${i <= current ? (disputed && i === current ? "bg-rose-400" : "bg-indigo-400") : "bg-white/10"}`} />
          )}
          <div className={`flex items-center gap-1 whitespace-nowrap px-1 ${i === 0 ? "" : i === steps.length - 1 ? "flex-row-reverse" : ""}`}>
            {disputed && i === current ? (
              <IconAlert className="h-4 w-4 text-rose-400" />
            ) : i <= current ? (
              <IconCircleCheck className="h-4 w-4 text-indigo-400" />
            ) : (
              <IconCircle className="h-4 w-4 text-slate-600" />
            )}
            <span className={`text-[11px] font-medium ${
              disputed && i === current ? "text-rose-400" : i <= current ? "text-indigo-300" : "text-slate-500"}`}>
              {label}
            </span>
          </div>
        </div>
      ))}
    </div>
  );
}

type IconProps = { className?: string };
const base = "stroke-current fill-none";

export function IconCheck({ className = "h-4 w-4" }: IconProps) {
  return <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2.5} strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12" /></svg>;
}
export function IconReceipt({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M6 3h12v18l-3-2-3 2-3-2-3 2V3z" />
      <line x1="9" y1="8" x2="15" y2="8" /><line x1="9" y1="12" x2="15" y2="12" />
    </svg>
  );
}
export function IconClock({ className = "h-4 w-4" }: IconProps) {
  return <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 3" /></svg>;
}
export function IconAlert({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="9" /><line x1="12" y1="8" x2="12" y2="13" /><circle cx="12" cy="16.5" r="0.75" fill="currentColor" stroke="none" />
    </svg>
  );
}
export function IconArrowUpRight({ className = "h-4 w-4" }: IconProps) {
  return <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2.25} strokeLinecap="round" strokeLinejoin="round"><line x1="7" y1="17" x2="17" y2="7" /><polyline points="8 7 17 7 17 16" /></svg>;
}
export function IconArrowDownLeft({ className = "h-4 w-4" }: IconProps) {
  return <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2.25} strokeLinecap="round" strokeLinejoin="round"><line x1="17" y1="7" x2="7" y2="17" /><polyline points="16 17 7 17 7 8" /></svg>;
}
export function IconPlus({ className = "h-4 w-4" }: IconProps) {
  return <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2.25} strokeLinecap="round"><line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" /></svg>;
}
export function IconChevronRight({ className = "h-4 w-4" }: IconProps) {
  return <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"><polyline points="9 6 15 12 9 18" /></svg>;
}
export function IconHome({ className = "h-4 w-4" }: IconProps) {
  return <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round"><path d="M4 11l8-7 8 7" /><path d="M6 10v10h12V10" /></svg>;
}
export function IconCar({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 16V11l2-5h12l2 5v5" /><path d="M4 16h16" />
      <circle cx="7.5" cy="17.5" r="1.5" fill="currentColor" stroke="none" /><circle cx="16.5" cy="17.5" r="1.5" fill="currentColor" stroke="none" />
    </svg>
  );
}
export function IconUtensils({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M7 3v7a2 2 0 0 0 2 2v9M7 3v7M9 3v7" />
      <path d="M17 3c-1.5 0-3 1.5-3 4s1.5 4 1.5 4V21" />
    </svg>
  );
}
export function IconCircle({ className = "h-4 w-4" }: IconProps) {
  return <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2}><circle cx="12" cy="12" r="8" /></svg>;
}
export function IconCircleCheck({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`h-4 w-4 fill-current ${className}`}>
      <circle cx="12" cy="12" r="9" />
      <path d="M8.5 12.3l2.2 2.2 4.5-4.8" fill="none" stroke="#14122a" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
export function IconUsers({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <circle cx="9" cy="8" r="3" /><path d="M3 20c0-3 2.5-5 6-5s6 2 6 5" />
      <circle cx="17" cy="9" r="2.3" /><path d="M15.5 15.2c2.7.3 4.5 2 4.5 4.8" />
    </svg>
  );
}
export function IconPencil({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z" />
    </svg>
  );
}
export function IconTrash({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <polyline points="3 6 5 6 21 6" />
      <path d="M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
      <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
      <line x1="10" y1="11" x2="10" y2="17" /><line x1="14" y1="11" x2="14" y2="17" />
    </svg>
  );
}
export function IconAdjustments({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <line x1="4" y1="6" x2="20" y2="6" /><circle cx="9" cy="6" r="2" fill="currentColor" stroke="none" />
      <line x1="4" y1="12" x2="20" y2="12" /><circle cx="15" cy="12" r="2" fill="currentColor" stroke="none" />
      <line x1="4" y1="18" x2="20" y2="18" /><circle cx="7" cy="18" r="2" fill="currentColor" stroke="none" />
    </svg>
  );
}
export function IconShieldCheck({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 3l7 3v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z" />
      <polyline points="9 12 11 14 15 10" />
    </svg>
  );
}
export function IconRefresh({ className = "h-4 w-4" }: IconProps) {
  return (
    <svg viewBox="0 0 24 24" className={`${base} ${className}`} strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 12a8 8 0 0 1 14-5.3" /><polyline points="16 4 18.5 6.5 16 9" />
      <path d="M20 12a8 8 0 0 1-14 5.3" /><polyline points="8 20 5.5 17.5 8 15" />
    </svg>
  );
}

export function categoryIcon(category: string): (p: IconProps) => JSX.Element {
  const c = category.toLowerCase();
  if (/food|grocer|restaurant|dinner|lunch|breakfast|snack/.test(c)) return IconUtensils;
  if (/travel|transport|cab|taxi|flight|fuel|scooter|car|bus/.test(c)) return IconCar;
  if (/rent|home|house|villa|stay|hotel|room/.test(c)) return IconHome;
  return IconReceipt;
}

export function notificationVisual(type: string): { Icon: (p: IconProps) => JSX.Element; tone: string } {
  switch (type) {
    case "EXPENSE_ADDED": return { Icon: IconReceipt, tone: "text-indigo-300 bg-indigo-400/20" };
    case "PAYMENT_CLAIMED": return { Icon: IconClock, tone: "text-amber-300 bg-amber-400/20" };
    case "PAYMENT_ACKNOWLEDGED": return { Icon: IconCheck, tone: "text-emerald-300 bg-emerald-400/20" };
    case "PAYMENT_DISPUTED": return { Icon: IconAlert, tone: "text-rose-300 bg-rose-400/20" };
    case "PAYMENT_DUE": return { Icon: IconClock, tone: "text-amber-300 bg-amber-400/20" };
    case "PAYMENT_OVERDUE": return { Icon: IconAlert, tone: "text-rose-300 bg-rose-400/20" };
    default: return { Icon: IconReceipt, tone: "text-slate-300 bg-white/10" };
  }
}
