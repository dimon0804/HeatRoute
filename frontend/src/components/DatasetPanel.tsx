import { useRef } from 'react'
import type { Dataset } from '../api/types'
import { bytes, dateTime, flow, meters, plural, RESTRICTION_LABELS } from '../lib/format'
import { Badge, Button, Empty, Row, Section } from './ui'

interface Props {
  datasets: Dataset[]
  selected: Dataset | null
  uploading: boolean
  onUpload: (file: File) => void
  onSelect: (dataset: Dataset) => void
  onDelete: (dataset: Dataset) => void
}

/** Загрузка конкурсного набора и сводка по нему. */
export function DatasetPanel({ datasets, selected, uploading, onUpload, onSelect, onDelete }: Props) {
  const input = useRef<HTMLInputElement>(null)

  return (
    <>
      <Section
        title="Конкурсный набор"
        hint="Один файл GeoJSON типа FeatureCollection в WGS 84"
        right={
          <Button onClick={() => input.current?.click()} disabled={uploading}>
            {uploading ? 'Загрузка…' : 'Загрузить'}
          </Button>
        }
      >
        <input
          ref={input}
          type="file"
          accept=".geojson,.json,application/geo+json,application/json"
          className="hidden"
          onChange={(event) => {
            const file = event.target.files?.[0]
            if (file) onUpload(file)
            event.target.value = ''
          }}
        />

        {datasets.length === 0 ? (
          <Empty>Наборов пока нет. Загрузите файл, чтобы начать.</Empty>
        ) : (
          <ul className="space-y-1">
            {datasets.map((dataset) => (
              <li key={dataset.id}>
                <button
                  type="button"
                  onClick={() => onSelect(dataset)}
                  className={
                    'w-full rounded-md border px-3 py-2 text-left transition-colors ' +
                    (selected?.id === dataset.id
                      ? 'border-accent/60 bg-accent/10'
                      : 'border-edge hover:bg-edge/50')
                  }
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate text-[13px] text-slate-100">{dataset.originalName}</span>
                    <StatusBadge status={dataset.status} />
                  </div>
                  <div className="mt-0.5 text-[11px] text-muted">
                    {bytes(dataset.sizeBytes)} · {plural(dataset.featureCount, 'объект', 'объекта', 'объектов')}
                    {' · '}
                    {dateTime(dataset.uploadedAt)}
                  </div>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Section>

      {selected?.summary && (
        <Section title="Что в наборе" hint="Значения после разбора и восстановления атрибутов">
          <Row label="Перспективных ОКС" value={selected.summary.futureOksCount} accent />
          <Row label="Суммарный расход" value={flow(selected.summary.totalFutureFlowTph)} mono />
          <Row label="Существующая сеть" value={meters(selected.summary.existingNetworkLength)} mono />
          <Row
            label="Участков сети"
            value={selected.summary.objectCounts?.heat_network ?? 0}
          />
          <Row label="Тепловых камер" value={selected.summary.objectCounts?.heat_chamber ?? 0} />

          <div className="mt-3 border-t border-edge pt-2">
            <p className="mb-1 text-[11px] uppercase tracking-wide text-muted">Условные диаметры сети</p>
            <div className="flex flex-wrap gap-1">
              {Object.entries(selected.summary.existingDiameters ?? {})
                .sort((a, b) => Number(a[0]) - Number(b[0]))
                .map(([dn, count]) => (
                  <Badge key={dn}>
                    ДУ {dn} — {count}
                  </Badge>
                ))}
            </div>
          </div>

          <div className="mt-3 border-t border-edge pt-2">
            <p className="mb-1 text-[11px] uppercase tracking-wide text-muted">Пространственные ограничения</p>
            <div className="space-y-0.5">
              {Object.entries(selected.summary.restrictionTypes ?? {})
                .sort((a, b) => b[1] - a[1])
                .map(([type, count]) => (
                  <Row key={type} label={RESTRICTION_LABELS[type] ?? type} value={count} />
                ))}
            </div>
          </div>

          {selected.id && (
            <div className="mt-4 flex justify-end">
              <Button variant="danger" onClick={() => onDelete(selected)}>
                Удалить набор
              </Button>
            </div>
          )}
        </Section>
      )}

      {selected?.errorMessage && (
        <Section title="Ошибка разбора">
          <p className="text-[13px] text-alert">{selected.errorMessage}</p>
        </Section>
      )}
    </>
  )
}

function StatusBadge({ status }: { status: Dataset['status'] }) {
  switch (status) {
    case 'READY':
      return <Badge tone="good">Готов</Badge>
    case 'PARSING':
      return <Badge tone="info">Разбор</Badge>
    case 'INVALID':
      return <Badge tone="warn">С замечаниями</Badge>
    default:
      return <Badge tone="bad">Ошибка</Badge>
  }
}
