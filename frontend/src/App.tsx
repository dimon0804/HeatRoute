import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from './api/client'
import type { Dataset, ForbiddenZone, Job } from './api/types'
import { DatasetPanel } from './components/DatasetPanel'
import { DiagnosticsPanel } from './components/DiagnosticsPanel'
import { Legend } from './components/Legend'
import { MapView } from './components/MapView'
import { SegmentsPanel } from './components/SegmentsPanel'
import { VariantsPanel } from './components/VariantsPanel'
import { ZonesPanel } from './components/ZonesPanel'
import { Badge, Button, Progress } from './components/ui'
import { duration, JOB_STATUS_LABELS } from './lib/format'

type Tab = 'data' | 'diagnostics' | 'variants' | 'segments'

const TABS: { key: Tab; label: string }[] = [
  { key: 'data', label: 'Данные' },
  { key: 'diagnostics', label: 'Разбор' },
  { key: 'variants', label: 'Варианты' },
  { key: 'segments', label: 'Участки' },
]

/**
 * Рабочий экран сервиса.
 * <p>
 * Состав и порядок панелей повторяют сценарий демонстрации из раздела 4 ТЗ:
 * загрузить набор, посмотреть разбор, запустить расчёт, увидеть построенную сеть
 * с врезками и расходами, сравнить варианты, выгрузить результат.
 */
export default function App() {
  const [datasets, setDatasets] = useState<Dataset[]>([])
  const [dataset, setDataset] = useState<Dataset | null>(null)
  const [job, setJob] = useState<Job | null>(null)
  const [scene, setScene] = useState<GeoJSON.FeatureCollection | null>(null)
  const [result, setResult] = useState<GeoJSON.FeatureCollection | null>(null)
  const [activeVariant, setActiveVariant] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('data')
  const [uploading, setUploading] = useState(false)
  const [basemap, setBasemap] = useState(false)
  const [withDepth, setWithDepth] = useState(false)
  const [networkLoaded, setNetworkLoaded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fitKey, setFitKey] = useState<string | null>(null)
  const [selectedFeatureId, setSelectedFeatureId] = useState<string | null>(null)
  const [zones, setZones] = useState<ForbiddenZone[]>([])
  const [zoneRadius, setZoneRadius] = useState(40)
  const [placingZone, setPlacingZone] = useState(false)

  // --- список наборов при старте ---------------------------------------------------------
  useEffect(() => {
    api.listDatasets()
      .then((list) => {
        setDatasets(list)
        // Открываем первый набор, который вообще поддаётся расчёту: в списке могут
        // лежать файлы, отвергнутые разбором, и открывать их при старте незачем.
        const usable = list.find((d) => d.status === 'READY' || d.status === 'INVALID')
        if (usable && !dataset) {
          void selectDataset(usable)
        }
      })
      .catch((e: Error) => setError(e.message))
    // Список загружается один раз: дальше он меняется только нашими же действиями.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const selectDataset = useCallback(async (next: Dataset) => {
    setDataset(next)
    setJob(null)
    setResult(null)
    setActiveVariant(null)
    setError(null)
    if (next.status === 'FAILED') {
      // Файл не разобран: показывать по нему нечего, причина видна в панели набора.
      setScene(null)
      return
    }
    try {
      const geo = await api.fetchDatasetSource(next.id)
      setScene(geo?.features ? geo : null)
      setFitKey(`${next.id}:${Date.now()}`)
    } catch (e) {
      setScene(null)
      setError((e as Error).message)
    }
  }, [])

  const handleUpload = useCallback(async (file: File) => {
    setUploading(true)
    setError(null)
    try {
      const uploaded = await api.uploadDataset(file)
      setDatasets((prev) => [uploaded, ...prev.filter((d) => d.id !== uploaded.id)])
      await selectDataset(uploaded)
      // Разобранный набор открывается на протоколе разбора: там самое важное,
      // что о нём известно. У неразобранного протокол пуст, а причина написана
      // в панели набора — уводить с неё на пустую вкладку незачем.
      setTab(uploaded.status === 'FAILED' ? 'data' : 'diagnostics')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : (e as Error).message)
    } finally {
      setUploading(false)
    }
  }, [selectDataset])

  const handleDelete = useCallback(async (target: Dataset) => {
    try {
      await api.deleteDataset(target.id)
      const rest = datasets.filter((d) => d.id !== target.id)
      setDatasets(rest)
      if (dataset?.id === target.id) {
        setDataset(null)
        setScene(null)
        setJob(null)
        setResult(null)
        if (rest.length > 0) void selectDataset(rest[0])
      }
    } catch (e) {
      setError((e as Error).message)
    }
  }, [datasets, dataset, selectDataset])

  const handleRun = useCallback(async () => {
    if (!dataset) return
    setError(null)
    setResult(null)
    setActiveVariant(null)
    setSelectedFeatureId(null)
    setTab('variants')
    try {
      const created = await api.submitJob(dataset.id, withDepth, zones, networkLoaded)
      setJob(created)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : (e as Error).message)
    }
  }, [dataset, withDepth, zones, networkLoaded])

  // --- запретные зоны ---------------------------------------------------------------------
  const handlePlaceZone = useCallback((lon: number, lat: number) => {
    setZones((prev) => [...prev, { lon, lat, radiusM: zoneRadius }])
  }, [zoneRadius])

  // --- опрос состояния расчёта ------------------------------------------------------------
  useEffect(() => {
    if (!job || (job.status !== 'QUEUED' && job.status !== 'RUNNING')) return
    const timer = window.setInterval(async () => {
      try {
        const fresh = await api.getJob(job.id)
        setJob(fresh)
        if (fresh.status === 'COMPLETED') {
          const geo = await api.fetchResult(fresh.id)
          setResult(geo)
          setActiveVariant(fresh.variants[0]?.variantCode ?? null)
        }
      } catch (e) {
        setError((e as Error).message)
      }
    }, 1200)
    return () => window.clearInterval(timer)
  }, [job])

  const canRun = Boolean(dataset && dataset.status !== 'FAILED'
    && job?.status !== 'RUNNING' && job?.status !== 'QUEUED')

  const headline = useMemo(() => {
    if (!job) return null
    if (job.status === 'COMPLETED') {
      const best = job.variants[0]
      return best
        ? `Лучший вариант: S = ${best.summary.score}, ${job.variants.length} вариант(а) за ${duration(job.durationMillis)}`
        : 'Расчёт завершён'
    }
    return job.stage ?? JOB_STATUS_LABELS[job.status]
  }, [job])

  return (
    <div className="flex h-screen w-screen flex-col bg-ink text-slate-100">
      {/* --- верхняя панель ------------------------------------------------------------ */}
      <header className="flex shrink-0 items-center justify-between gap-4 border-b border-edge bg-panel px-4 py-2.5">
        <div className="flex items-baseline gap-3">
          <h1 className="text-[15px] font-semibold tracking-tight">HeatRoute</h1>
          <span className="text-[12px] text-muted">
            моделирование трасс подключения к тепловым сетям
          </span>
        </div>

        <div className="flex items-center gap-3">
          {headline && (
            <span className="text-[12px] text-muted">{headline}</span>
          )}
          <label
            className="flex cursor-pointer items-center gap-1.5 text-[12px] text-muted"
            title="Дополнительная задача кейса: подбор глубины участков, прохождение
                   пересечений сверху или снизу, коэффициент стоимости по глубине"
          >
            <input
              type="checkbox"
              checked={withDepth}
              onChange={(e) => setWithDepth(e.target.checked)}
              className="accent-accent"
            />
            с учётом глубины
          </label>
          <label
            className="flex cursor-pointer items-center gap-1.5 text-[12px] text-muted"
            title="Расхода существующей сети во входных данных нет, и по умолчанию он принят
                   нулевым. Здесь он принимается равным половине пропускной способности —
                   так видно, во что обходится это допущение по объёму реконструкции"
          >
            <input
              type="checkbox"
              checked={networkLoaded}
              onChange={(e) => setNetworkLoaded(e.target.checked)}
              className="accent-accent"
            />
            сеть загружена
          </label>
          <label className="flex cursor-pointer items-center gap-1.5 text-[12px] text-muted">
            <input
              type="checkbox"
              checked={basemap}
              onChange={(e) => setBasemap(e.target.checked)}
              className="accent-accent"
            />
            подложка OSM
          </label>
          <Button onClick={handleRun} disabled={!canRun}>
            {job?.status === 'RUNNING' || job?.status === 'QUEUED' ? 'Расчёт идёт…' : 'Запустить расчёт'}
          </Button>
        </div>
      </header>

      {error && (
        <div className="shrink-0 border-b border-tie/40 bg-tie/10 px-4 py-2 text-[12.5px] text-tie">
          {error}
          <button type="button" className="ml-3 underline" onClick={() => setError(null)}>
            скрыть
          </button>
        </div>
      )}

      <div className="flex min-h-0 flex-1">
        {/* --- боковая панель -------------------------------------------------------- */}
        <aside className="flex w-[380px] shrink-0 flex-col border-r border-edge bg-panel">
          <nav className="flex shrink-0 border-b border-edge">
            {TABS.map((item) => (
              <button
                key={item.key}
                type="button"
                onClick={() => setTab(item.key)}
                className={
                  'flex-1 border-b-2 px-2 py-2 text-[12.5px] transition-colors ' +
                  (tab === item.key
                    ? 'border-accent text-slate-100'
                    : 'border-transparent text-muted hover:text-slate-300')
                }
              >
                {item.label}
                {item.key === 'diagnostics' && dataset && dataset.diagnostics.length > 0 && (
                  <span className="ml-1.5 align-middle">
                    <Badge tone="info">{dataset.diagnostics.length}</Badge>
                  </span>
                )}
                {item.key === 'variants' && job?.variants?.length ? (
                  <span className="ml-1.5 align-middle">
                    <Badge tone="good">{job.variants.length}</Badge>
                  </span>
                ) : null}
              </button>
            ))}
          </nav>

          <div className="min-h-0 flex-1 overflow-y-auto">
            {tab === 'data' && (
              <DatasetPanel
                datasets={datasets}
                selected={dataset}
                uploading={uploading}
                onUpload={handleUpload}
                onSelect={(d) => void selectDataset(d)}
                onDelete={(d) => void handleDelete(d)}
              />
            )}
            {tab === 'diagnostics' && (
              <DiagnosticsPanel entries={dataset?.diagnostics ?? []} />
            )}
            {tab === 'variants' && (
              <VariantsPanel
                job={job}
                activeVariant={activeVariant}
                onSelectVariant={setActiveVariant}
                onExport={() => job && window.open(api.resultUrl(job.id), '_blank')}
                onExportStatement={() => job
                  && window.open(api.statementUrl(job.id, activeVariant), '_blank')}
              />
            )}
            {tab === 'segments' && (
              <SegmentsPanel
                result={result}
                activeVariant={activeVariant}
                selectedFeatureId={selectedFeatureId}
                onSelectFeature={setSelectedFeatureId}
              />
            )}
          </div>

          <div className="shrink-0">
            <ZonesPanel
              zones={zones}
              radiusM={zoneRadius}
              placing={placingZone}
              onRadiusChange={setZoneRadius}
              onTogglePlacing={() => setPlacingZone((v) => !v)}
              onRemove={(index) => setZones((prev) => prev.filter((_, i) => i !== index))}
              onClear={() => setZones([])}
            />
          </div>

          {(job?.status === 'RUNNING' || job?.status === 'QUEUED') && (
            <div className="shrink-0 border-t border-edge px-4 py-3">
              <Progress value={job.progress} label={job.stage ?? 'Подготовка'} />
            </div>
          )}
        </aside>

        {/* --- карта ------------------------------------------------------------------- */}
        <main className="relative min-w-0 flex-1">
          <MapView
            scene={scene}
            result={result}
            activeVariant={activeVariant}
            showBasemap={basemap}
            fitKey={fitKey}
            selectedFeatureId={selectedFeatureId}
            zones={zones}
            placingZone={placingZone}
            onPlaceZone={handlePlaceZone}
          />
          <Legend />
        </main>
      </div>
    </div>
  )
}
