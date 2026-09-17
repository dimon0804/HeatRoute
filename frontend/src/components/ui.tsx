import clsx from 'clsx'
import type { ReactNode } from 'react'

/** Мелкие элементы интерфейса, общие для всех панелей. */

export function Section({ title, hint, children, right }: {
  title: string
  hint?: string
  right?: ReactNode
  children: ReactNode
}) {
  return (
    <section className="border-b border-edge">
      <header className="flex items-baseline justify-between gap-3 px-4 pt-4 pb-2">
        <div>
          <h2 className="text-[13px] font-semibold uppercase tracking-wide text-muted">{title}</h2>
          {hint && <p className="mt-0.5 text-[11px] text-muted/70">{hint}</p>}
        </div>
        {right}
      </header>
      <div className="px-4 pb-4">{children}</div>
    </section>
  )
}

export function Row({ label, value, mono, accent }: {
  label: string
  value: ReactNode
  mono?: boolean
  accent?: boolean
}) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-[3px] text-[13px]">
      <span className="text-muted">{label}</span>
      <span className={clsx('text-right', mono && 'font-mono text-[12px]',
        accent ? 'text-accent font-semibold' : 'text-slate-100')}>
        {value}
      </span>
    </div>
  )
}

export function Button({ children, onClick, disabled, variant = 'primary', title }: {
  children: ReactNode
  onClick?: () => void
  disabled?: boolean
  variant?: 'primary' | 'ghost' | 'danger'
  title?: string
}) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      disabled={disabled}
      className={clsx(
        'rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors',
        'disabled:cursor-not-allowed disabled:opacity-40',
        variant === 'primary' && 'bg-accent text-ink hover:bg-accent/85',
        variant === 'ghost' && 'border border-edge text-slate-200 hover:bg-edge/60',
        variant === 'danger' && 'border border-tie/50 text-tie hover:bg-tie/10',
      )}
    >
      {children}
    </button>
  )
}

export function Badge({ children, tone = 'neutral' }: {
  children: ReactNode
  tone?: 'neutral' | 'good' | 'warn' | 'bad' | 'info'
}) {
  return (
    <span
      className={clsx(
        'rounded px-1.5 py-0.5 text-[11px] font-medium',
        tone === 'neutral' && 'bg-edge text-slate-300',
        tone === 'good' && 'bg-oks/15 text-oks',
        tone === 'warn' && 'bg-recon/15 text-recon',
        tone === 'bad' && 'bg-tie/15 text-tie',
        tone === 'info' && 'bg-accent/15 text-accent',
      )}
    >
      {children}
    </span>
  )
}

export function Progress({ value, label }: { value: number; label?: string }) {
  return (
    <div className="space-y-1">
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-edge">
        <div
          className="h-full rounded-full bg-accent transition-[width] duration-500"
          style={{ width: `${Math.round(value * 100)}%` }}
        />
      </div>
      {label && <p className="text-[11px] text-muted">{label}</p>}
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="py-6 text-center text-[13px] text-muted">{children}</p>
}
