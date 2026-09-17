import { useState } from 'react'
import type { DiagnosticsEntry, Severity } from '../api/types'
import { plural, SEVERITY_LABELS } from '../lib/format'
import { Badge, Empty, Section } from './ui'

/**
 * Протокол разбора входных данных.
 * <p>
 * Панель существует не для отладки. Критерий «диагностика данных» проверяется
 * экспертами напрямую, и на вопрос «откуда взялось это значение» здесь лежит
 * письменный ответ: что было восстановлено, по какому правилу и каких объектов
 * это коснулось.
 */
export function DiagnosticsPanel({ entries }: { entries: DiagnosticsEntry[] }) {
  const [expanded, setExpanded] = useState<string | null>(null)

  if (entries.length === 0) {
    return (
      <Section title="Протокол разбора">
        <Empty>Загрузите набор — здесь появится разбор входных данных.</Empty>
      </Section>
    )
  }

  const counts = entries.reduce<Record<string, number>>((acc, entry) => {
    acc[entry.severity] = (acc[entry.severity] ?? 0) + 1
    return acc
  }, {})

  return (
    <Section
      title="Протокол разбора"
      hint="Что восстановлено и какие допущения приняты"
    >
      <div className="mb-3 flex flex-wrap gap-1">
        {(['ERROR', 'WARNING', 'ASSUMPTION', 'INFO'] as Severity[])
          .filter((severity) => counts[severity])
          .map((severity) => (
            <Badge key={severity} tone={toneOf(severity)}>
              {SEVERITY_LABELS[severity]}: {counts[severity]}
            </Badge>
          ))}
      </div>

      <ul className="space-y-1.5">
        {entries.map((entry) => {
          const open = expanded === entry.code
          return (
            <li
              key={entry.code}
              className="rounded-md border border-edge bg-ink/40 px-3 py-2"
            >
              <div className="flex items-start gap-2">
                <span className={'mt-1 h-1.5 w-1.5 shrink-0 rounded-full ' + dotOf(entry.severity)} />
                <div className="min-w-0 flex-1">
                  <p className="text-[12.5px] leading-snug text-slate-200">{entry.message}</p>
                  <div className="mt-1 flex items-center gap-2">
                    <code className="text-[10.5px] text-muted/80">{entry.code}</code>
                    {entry.objectIds.length > 0 && (
                      <button
                        type="button"
                        className="text-[11px] text-accent hover:underline"
                        onClick={() => setExpanded(open ? null : entry.code)}
                      >
                        {open ? 'скрыть объекты' : plural(entry.objectIds.length, 'объект', 'объекта', 'объектов')}
                      </button>
                    )}
                  </div>
                  {open && (
                    <p className="mt-1.5 max-h-32 overflow-y-auto break-words font-mono text-[10.5px] leading-relaxed text-muted">
                      {entry.objectIds.join(', ')}
                    </p>
                  )}
                </div>
              </div>
            </li>
          )
        })}
      </ul>
    </Section>
  )
}

function toneOf(severity: Severity) {
  switch (severity) {
    case 'ERROR':
      return 'bad' as const
    case 'WARNING':
      return 'warn' as const
    case 'ASSUMPTION':
      return 'info' as const
    default:
      return 'neutral' as const
  }
}

function dotOf(severity: Severity) {
  switch (severity) {
    case 'ERROR':
      return 'bg-tie'
    case 'WARNING':
      return 'bg-recon'
    case 'ASSUMPTION':
      return 'bg-accent'
    default:
      return 'bg-muted'
  }
}
