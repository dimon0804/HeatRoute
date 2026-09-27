import { useEffect, useMemo, useState } from 'react'
import { api } from '../api/client'
import type { SegmentExplanation } from '../api/types'
import { flow, meters, money } from '../lib/format'
import { Badge, Button, Empty, Section } from './ui'
import { DepthProfile } from './DepthProfile'

interface Props {
  result: GeoJSON.FeatureCollection | null
  jobId: string | null
  activeVariant: string | null
  selectedFeatureId: string | null
  onSelectFeature: (id: string | null) => void
}

type Tab = 'segments' | 'chambers' | 'depth' | 'explain'

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
  result, jobId, activeVariant, selectedFeatureId, onSelectFeature,
}: Props) {
  const [tab, setTab] = useState<Tab>('segments')
  const [explain, setExplain] = useState<SegmentExplanation[] | null>(null)
  const [explainLoading, setExplainLoading] = useState(false)
  const [explainError, setExplainError] = useState<string | null>(null)

  // Разбор считается по запросу и заново при смене варианта: он про конкретную
  // трассу, и показывать рядом с одним вариантом разбор другого нельзя.
  useEffect(() => {
    setExplain(null)
    setExplainError(null)
  }, [jobId, activeVariant])

  // Флаг загрузки намеренно не в зависимостях: он меняется сразу после запуска
  // запроса, эффект перезапустился бы и отменил собственный же запрос.
  useEffect(() => {
    if (tab !== 'explain' || !jobId || explain || explainError) {
      return
    }
    let cancelled = false
    setExplainLoading(true)
    api.explain(jobId, activeVariant)
      .then((loaded) => { if (!cancelled) setExplain(loaded) })
      .catch((error: unknown) => {
        if (!cancelled) {
          setExplainError(error instanceof Error ? error.message : String(error))
        }
      })
      .finally(() => { if (!cancelled) setExplainLoading(false) })
    return () => { cancelled = true }
  }, [tab, jobId, activeVariant, explain, explainError])

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
            : tab === 'depth' ? 'Профиль по глубине'
              : 'Почему трасса прошла здесь'
      }
      hint={
        tab === 'segments'
          ? 'Расход, условный диаметр, способ прокладки и стоимость. Щелчок по строке показывает участок на карте'
          : tab === 'chambers'
            ? 'Наибольший диаметр примыкающих участков, число примыканий при пределе четыре и стоимость камеры'
            : tab === 'depth'
              ? 'Участки, которые отходят от обычной глубины 3,0 м. Щелчок по строке открывает продольный профиль магистрали'
              : 'Какое ограничение зажало участок, сколько запаса осталось и насколько он длиннее прямой'
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
          {jobId && (
            <TabButton active={tab === 'explain'} onClick={() => setTab('explain')}>
              Почему здесь
            </TabButton>
          )}
        </div>
      }
    >
      {tab === 'explain' ? (
        <ExplainTable
          rows={explain}
          loading={explainLoading}
          error={explainError}
          selectedFeatureId={selectedFeatureId}
          onSelectFeature={onSelectFeature}
          onRetry={() => { setExplainError(null); setExplain(null) }}
        />
      ) : tab === 'segments' ? (
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
        </div>
      )}
      {tab === 'depth' && selectedSegment && (
        <DepthProfile
          segments={rows.segments}
          focusSegmentId={String(selectedSegment.id)}
        />
      )}
    </Section>
  )
}

/**
 * Разбор трассы: по каждому участку видно, чем задано его место.
 *
 * Главное недоверие к автоматической трассировке звучит так: «почему труба
 * пошла тут, а не прямее». Числа в этой таблице и есть ответ: фактическое
 * расстояние до ограничения против требуемого и запас между ними.
 */
function ExplainTable({
  rows, loading, error, selectedFeatureId, onSelectFeature, onRetry,
}: {
  rows: SegmentExplanation[] | null
  loading: boolean
  error: string | null
  selectedFeatureId: string | null
  onSelectFeature: (id: string | null) => void
  onRetry: () => void
}) {
  if (error) {
    return (
      <div className="space-y-2">
        <p className="text-[12.5px] text-alert">{error}</p>
        <Button variant="ghost" onClick={onRetry}>Попробовать снова</Button>
      </div>
    )
  }
  if (loading || !rows) {
    return <Empty>Разбираю трассу…</Empty>
  }
  if (rows.length === 0) {
    return <Empty>Участков у этого варианта нет.</Empty>
  }

  const selected = rows.find((row) => row.segmentId === selectedFeatureId)

  return (
    <>
      <div className="max-h-56 overflow-y-auto">
      <table className="w-full text-[12px]">
        <thead className="sticky top-0 bg-panel text-[11px] uppercase text-muted">
          <tr>
            <th className="py-1 text-left font-medium">Участок</th>
            <th className="py-1 text-right font-medium">Длиннее прямой</th>
            <th className="py-1 text-right font-medium">Запас</th>
            <th className="py-1 pl-2 text-left font-medium">Чем зажат</th>
          </tr>
        </thead>
        <tbody className="font-mono">
          {rows.map((row) => {
            const binding = row.nearby.filter((item) => item.binding)
            return (
              <tr
                key={row.segmentId}
                onClick={() => onSelectFeature(
                  selectedFeatureId === row.segmentId ? null : row.segmentId)}
                className={
                  'cursor-pointer border-t border-edge/60 transition-colors ' +
                  (selectedFeatureId === row.segmentId
                    ? 'bg-accent/15' : 'hover:bg-edge/40')
                }
              >
                <td className="py-1 pr-2 text-slate-300">{row.segmentId}</td>
                <td className="py-1 text-right text-slate-200">
                  {row.detourShare > 0.001
                    ? `${(row.detourShare * 100).toFixed(0)} %`
                    : <span className="text-muted">прямая</span>}
                </td>
                <td className="py-1 text-right whitespace-nowrap">
                  {row.tightestMarginM == null ? (
                    <span className="text-muted">—</span>
                  ) : (
                    <span className={row.tightestMarginM <= 0.5
                      ? 'text-warn' : 'text-slate-200'}>
                      {row.tightestMarginM.toFixed(2)} м
                    </span>
                  )}
                </td>
                <td className="py-1 pl-2 text-slate-400">
                  {binding.length === 0
                    ? <span className="text-muted">свободно</span>
                    : binding.map((item) => `${item.type} ${item.restrictionId}`).join(', ')}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      </div>
      {selected && (
        <div className="mt-2 rounded border border-edge/60 bg-edge/20 p-2">
          <p className="text-[12.5px] leading-snug text-slate-200">{selected.verdict}</p>
          {selected.nearby.length > 0 && (
            <table className="mt-2 w-full font-mono text-[11.5px]">
              <thead className="text-[10.5px] uppercase text-muted">
                <tr>
                  <th className="py-0.5 text-left font-medium">Ограничение</th>
                  <th className="py-0.5 text-right font-medium">Факт</th>
                  <th className="py-0.5 text-right font-medium">Нужно</th>
                  <th className="py-0.5 text-right font-medium">Запас</th>
                </tr>
              </thead>
              <tbody>
                {selected.nearby.slice(0, 6).map((item) => (
                  <tr key={`${item.type}-${item.restrictionId}`}
                    className="border-t border-edge/40">
                    <td className="py-0.5 pr-2 text-slate-300">
                      {item.type} {item.restrictionId}
                      {item.attachment && (
                        <span className="ml-1"><Badge>примыкание</Badge></span>
                      )}
                      {item.binding && (
                        <span className="ml-1"><Badge tone="warn">по пределу</Badge></span>
                      )}
                    </td>
                    <td className="py-0.5 text-right text-slate-200">
                      {item.distanceM.toFixed(2)}
                    </td>
                    <td className="py-0.5 text-right text-muted">
                      {item.requiredM.toFixed(2)}
                    </td>
                    <td className="py-0.5 text-right text-slate-400">
                      {item.marginM.toFixed(2)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </>
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
