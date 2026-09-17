import { useState } from 'react'
import { LEGEND } from '../lib/mapStyle'

/** Легенда карты. Свёрнута по умолчанию: на демонстрации важнее сама карта. */
export function Legend() {
  const [open, setOpen] = useState(true)

  const groups = Array.from(new Set(LEGEND.map((item) => item.group)))

  return (
    <div className="pointer-events-auto absolute bottom-4 right-4 w-64 rounded-lg border border-edge bg-panel/95 shadow-xl backdrop-blur">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="flex w-full items-center justify-between px-3 py-2 text-[12px] font-semibold uppercase tracking-wide text-muted"
      >
        Условные обозначения
        <span className="text-[14px] leading-none">{open ? '−' : '+'}</span>
      </button>

      {open && (
        <div className="space-y-2 px-3 pb-3">
          {groups.map((group) => (
            <div key={group}>
              <p className="mb-1 text-[10.5px] uppercase tracking-wide text-muted/70">{group}</p>
              <ul className="space-y-1">
                {LEGEND.filter((item) => item.group === group).map((item) => (
                  <li key={item.label} className="flex items-center gap-2">
                    <Swatch color={item.color} shape={item.shape} />
                    <span className="text-[11.5px] leading-tight text-slate-300">{item.label}</span>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function Swatch({ color, shape }: { color: string; shape: 'line' | 'dashed' | 'circle' | 'area' }) {
  if (shape === 'circle') {
    return (
      <span
        className="h-2.5 w-2.5 shrink-0 rounded-full border border-black/50"
        style={{ backgroundColor: color }}
      />
    )
  }
  if (shape === 'area') {
    return (
      <span
        className="h-2.5 w-4 shrink-0 rounded-sm border border-white/10"
        style={{ backgroundColor: color }}
      />
    )
  }
  return (
    <span
      className="h-0.5 w-4 shrink-0 rounded"
      style={{
        backgroundColor: shape === 'dashed' ? 'transparent' : color,
        backgroundImage:
          shape === 'dashed'
            ? `repeating-linear-gradient(90deg, ${color} 0 4px, transparent 4px 7px)`
            : undefined,
      }}
    />
  )
}
