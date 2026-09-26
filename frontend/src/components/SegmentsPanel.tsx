import { useMemo, useState } from 'react'
import { flow, meters, money } from '../lib/format'
import { Badge, Empty, Section } from './ui'
import { DepthProfile } from './DepthProfile'

interface Props {
  result: GeoJSON.FeatureCollection | null
  activeVariant: string | null
  selectedFeatureId: string | null
  onSelectFeature: (id: string | null) => void
}

type Tab = 'segments' | 'chambers' | 'depth'

/** Обычная глубина заложения, от которой считается отклонение профиля. */
const NORMAL_DEPTH = 3.0
/** Предельный уклон при смене глубины, м/м. */
const MAX_SLOPE = 0.1
/** Сколько линейных участков может примыкать к тепловой камере. */
const MAX_CHAMBER_DEGREE = 4

/**
 * Таблицы новой сети: участки, тепловые камеры и профиль по глубине.
 * <p>
 * Карта отвечает на вопрос «где», таблица — на вопрос «сколько». Расходы,
 * условные диаметры, способ прокладки и стоимость собраны в одном месте,
 * а щелчок по строке показывает объект на карте.
 */
export function SegmentsPanel({
  result, activeVariant, selectedFeatureId, onSelectFeature,
}: Props) {
  const [tab, setTab] = useState<Tab>('segments')

  const rows = useMemo(() => {
    if (!result?.features) {
      return { segments: [], chambers: [], depth: [], depthMode: false }
    }
    const match = (props: Record<string, unknown>) =>
      !activeVariant || props.variant_id === activeVariant

    const segments = result.features
      .filter((f) => f.properties?.object_type === 'heat_network' && match(f.properties ?? {}))
      .map((f) => f.properties as Record<string, unknown>)
      .sort((a, b) => Number(b.flow_tph) - Number(a.flow_tph))

    // Камеры по убыванию диаметра: первыми идут узлы магистрали, где примыканий
    // больше всего и где предел в четыре участка ближе всего к исчерпанию.
    const chambers = result.features
      .filter((f) => f.properties?.object_type === 'heat_chamber' && match(f.properties ?? {}))
      .map((f) => f.properties as Record<string, unknown>)
      .sort((a, b) => Number(b.diameter) - Number(a.diameter)
        || Number(b.degree ?? 0) - Number(a.degree ?? 0))

    // Профиль по глубине собирается из самих участков: отдельных объектов
    // пересечения выгрузка не содержит, вертикаль задана глубинами концов.
    // В таблицу попадают участки, которые отходят от обычной отметки 3,0 м, —
    // именно там трасса обходит существующие коммуникации по вертикали.
    const depth = segments
      .filter((row) => row.depth_start != null && row.depth_end != null)
      .filter((row) => Math.abs(Number(row.depth_start) - NORMAL_DEPTH) > 1e-6
        || Math.abs(Number(row.depth_end) - NORMAL_DEPTH) > 1e-6)
      .sort((a, b) => Math.max(Number(b.depth_start), Number(b.depth_end))
        - Math.max(Number(a.depth_start), Number(a.depth_end)))

    // Режим с глубиной виден по самим данным: в двумерном расчёте глубины null,
    // и вкладка не нужна. Если глубины есть, а отклонений нет, вкладка остаётся:
    // это хороший результат, а не отсутствие расчёта.
    const depthMode = result.features.some((f) => f.properties?.object_type === 'heat_network'
      && f.properties?.depth_start != null)

    return { segments, chambers, depth, depthMode }
  }, [result, activeVariant])

  const selectedSegment = rows.depth.find((row) => String(row.id) === selectedFeatureId)

  if (!result?.features) {
    return (
      <Section title="Участки">
        <Empty>Здесь появятся участки построенной сети.</Empty>
      </Section>
    )
  }

  return (
    <Section
      title={
        tab === 'segments' ? 'Участки новой сети'
          : tab === 'chambers' ? 'Новые тепловые камеры'
            : 'Профиль по глубине'
      }
      hint={
        tab === 'segments'
          ? 'Расход, условный диаметр, способ прокладки и стоимость. Щелчок по строке показывает участок на карте'
          : tab === 'chambers'
            ? 'Наибольший диаметр примыкающих участков, число примыканий при пределе четыре и стоимость камеры'
            : 'Участки, которые отходят от обычной глубины 3,0 м. Щелчок по строке открывает продольный профиль магистрали'
      }
      stackRight
      right={
        <div className="flex flex-wrap gap-1">
          <TabButton active={tab === 'segments'} onClick={() => setTab('segments')}>
            Новые ({rows.segments.length})
          </TabButton>
          <TabButton active={tab === 'chambers'} onClick={() => setTab('chambers')}>
            Камеры ({rows.chambers.length})
          </TabButton>
          {rows.depthMode && (
            <TabButton active={tab === 'depth'} onClick={() => setTab('depth')}>
              Глубина ({rows.depth.length})
            </TabButton>
          )}
        </div>
      }
    >
      {tab === 'segments' ? (
        rows.segments.length === 0 ? (
          <Empty>Участков нет.</Empty>
        ) : (
          <div className="max-h-72 overflow-y-auto">
            <table className="w-full text-[12px]">
              <thead className="sticky top-0 bg-panel text-[11px] uppercase text-muted">
                <tr>
                  <th className="py-1 text-left font-medium">Участок</th>
                  <th className="py-1 text-right font-medium">Расход</th>
                  <th className="py-1 text-right font-medium">ДУ</th>
                  <th className="py-1 text-right font-medium">Длина</th>
                  <th className="py-1 text-right font-medium">Стоимость</th>
                </tr>
              </thead>
              <tbody className="font-mono">
                {rows.segments.map((row) => (
                  <tr
                    key={String(row.id)}
                    onClick={() => onSelectFeature(
                      selectedFeatureId === String(row.id) ? null : String(row.id))}
                    className={
                      'cursor-pointer border-t border-edge/60 transition-colors ' +
                      (selectedFeatureId === String(row.id)
                        ? 'bg-accent/15' : 'hover:bg-edge/40')
                    }
                  >
                    <td className="py-1 pr-2">
                      <span className="text-slate-300">{String(row.id)}</span>
                      {row.laying_method === 'special' && (
                        <span className="ml-1"><Badge tone="warn">спец</Badge></span>
                      )}
                    </td>
                    <td className="py-1 text-right text-slate-200">{flow(Number(row.flow_tph))}</td>
                    <td className="py-1 text-right text-accent">{String(row.diameter)}</td>
                    <td className="py-1 text-right text-slate-200">{meters(Number(row.length))}</td>
                    <td className="py-1 text-right text-slate-400">{money(Number(row.cost))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      ) : tab === 'chambers' ? (
        rows.chambers.length === 0 ? (
          <Empty>
            Новых тепловых камер у этого варианта нет: новая сеть примыкает
            к существующим камерам.
          </Empty>
        ) : (
          <div className="max-h-72 overflow-y-auto">
            <table className="w-full text-[12px]">
              <thead className="sticky top-0 bg-panel text-[11px] uppercase text-muted">
                <tr>
                  <th className="py-1 text-left font-medium">Камера</th>
                  <th className="py-1 text-right font-medium">ДУ</th>
                  <th className="py-1 text-right font-medium">Примыканий</th>
                  <th className="py-1 text-right font-medium">Стоимость</th>
                </tr>
              </thead>
              <tbody className="font-mono">
                {rows.chambers.map((row) => {
                  const degree = row.degree == null ? null : Number(row.degree)
                  return (
                    <tr
                      key={String(row.id)}
                      onClick={() => onSelectFeature(
                        selectedFeatureId === String(row.id) ? null : String(row.id))}
                      className={
                        'cursor-pointer border-t border-edge/60 transition-colors ' +
                        (selectedFeatureId === String(row.id)
                          ? 'bg-accent/15' : 'hover:bg-edge/40')
                      }
                    >
                      <td className="py-1 pr-2 text-slate-300">{String(row.id)}</td>
                      <td className="py-1 text-right text-accent">{String(row.diameter)}</td>
                      <td className="py-1 text-right whitespace-nowrap">
                        {degree == null ? (
                          <span className="text-muted">—</span>
                        ) : (
                          <>
                            <span className={degree >= MAX_CHAMBER_DEGREE
                              ? 'text-warn' : 'text-slate-200'}>
                              {degree}
                            </span>
                            <span className="text-muted"> из {MAX_CHAMBER_DEGREE}</span>
                          </>
                        )}
                      </td>
                      <td className="py-1 text-right text-slate-400">{money(Number(row.cost))}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )
      ) : rows.depth.length === 0 ? (
        <Empty>
          У этого варианта трасса идёт на обычной глубине 3,0 м целиком.
          Отклонения по глубине есть у других вариантов — переключите вариант
          в панели слева.
        </Empty>
      ) : (
        <div className="max-h-72 overflow-y-auto">
          <table className="w-full text-[12px]">
            <thead className="sticky top-0 bg-panel text-[11px] uppercase text-muted">
              <tr>
                <th className="py-1 pr-2 text-left font-medium">Участок</th>
                <th className="py-1 pr-2 text-left font-medium">Прокладка</th>
                <th className="py-1 pr-2 text-right font-medium">Глубина</th>
                <th className="py-1 text-right font-medium">Уклон</th>
              </tr>
            </thead>
            <tbody className="font-mono">
              {rows.depth.map((row) => {
                const from = Number(row.depth_start)
                const to = Number(row.depth_end)
                const length = Number(row.length)
                const slope = length > 0 ? Math.abs(to - from) / length : 0
                return (
                  <tr
                    key={String(row.id)}
                    onClick={() => onSelectFeature(
                      selectedFeatureId === String(row.id) ? null : String(row.id))}
                    className={
                      'cursor-pointer border-t border-edge/60 transition-colors ' +
                      (selectedFeatureId === String(row.id)
                        ? 'bg-accent/15' : 'hover:bg-edge/40')
                    }
                  >
                    <td className="py-1 pr-2 text-slate-300">{String(row.id)}</td>
                    <td className="py-1 pr-2">
                      {row.laying_method === 'special'
                        ? <Badge tone="warn">спец</Badge>
                        : <span className="text-muted">обычная</span>}
                    </td>
                    <td className="py-1 pr-2 text-right whitespace-nowrap">
                      <span className="text-slate-400">{from.toFixed(1)}</span>
                      <span className="text-muted"> → </span>
                      <span className="text-accent">{to.toFixed(1)}</span>
                      <span className="text-muted"> м</span>
                    </td>
                    <td className="py-1 text-right whitespace-nowrap">
                      <span className={slope > MAX_SLOPE + 1e-9 ? 'text-alert' : 'text-slate-200'}>
                        {slope.toFixed(3)}
                      </span>
                      <span className="text-muted"> м/м</span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          {selectedSegment && (
            <DepthProfile
              segments={rows.segments}
              focusSegmentId={String(selectedSegment.id)}
            />
          )}
        </div>
      )}
    </Section>
  )
}

function TabButton({ active, onClick, children }: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        'rounded px-2 py-1 text-[11px] transition-colors ' +
        (active ? 'bg-accent/15 text-accent' : 'text-muted hover:text-slate-200')
      }
    >
      {children}
    </button>
  )
}
