/**
 * Типы ответов API. Повторяют DTO бэкенда один в один — именно поэтому они собраны
 * в одном файле: при изменении контракта расхождение видно сразу, а не по месту
 * использования.
 */

/**
 * Запретная зона: круг на местности, через который трасса не пройдёт.
 * Задаётся пользователем на карте — «здесь копать нельзя».
 */
export interface ForbiddenZone {
  lon: number
  lat: number
  radiusM: number
}

export type DatasetStatus = 'PARSING' | 'READY' | 'INVALID' | 'FAILED'
export type JobStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
export type Severity = 'INFO' | 'ASSUMPTION' | 'WARNING' | 'ERROR'

export interface SceneSummary {
  objectCounts: Record<string, number>
  futureOksCount: number
  totalFutureFlowTph: number
  existingNetworkLength: number
  existingDiameters: Record<string, number>
  restrictionTypes: Record<string, number>
  extentMeters?: [number, number]
  /** [minLon, minLat, maxLon, maxLat] */
  bboxWgs84?: [number, number, number, number]
}

export interface DiagnosticsEntry {
  severity: Severity
  code: string
  message: string
  objectIds: string[]
}

export interface Dataset {
  id: string
  originalName: string
  sizeBytes: number
  featureCount: number
  uploadedAt: string
  status: DatasetStatus
  errorMessage?: string
  summary?: SceneSummary
  diagnostics: DiagnosticsEntry[]
}

/**
 * Сводка по варианту, раздел 7.2 технического приложения.
 * <p>
 * Стоимость строительства складывается из трёх слагаемых: новые участки сети,
 * новые тепловые камеры и врезки в существующие тепловые камеры. Реконструкции
 * существующей сети в расчётной модели нет, поэтому её полей здесь тоже нет.
 */
export interface VariantSummary {
  rank: number
  /** Новые участки, новые камеры и врезки в существующие камеры вместе. */
  constructionCost: number
  chamberConstructionCost: number
  /** Сколько новых участков заканчивается в существующих тепловых камерах. */
  existingChamberTieInCount: number
  /** Стоимость этих врезок: 5 000 000 ₽ за каждую. */
  existingChamberTieInCost: number
  unconnectedPenalty: number
  calculatedCost: number
  newNetworkLength: number
  score: number
  /** Тип идентификатора сохраняется таким же, как во входных данных. */
  unconnectedOksIds: (string | number)[]
  /** Почему каждая точка осталась без подключения: идентификатор → причина. */
  unconnectedReasons?: Record<string, string>
}

export interface Variant {
  id: string
  variantCode: string
  description: string
  summary: VariantSummary
  featureCounts: Record<string, number>
}

export interface JobStats {
  designDiameter: number
  graphNodes: number
  graphEdges: number
  /** Сколько мест присоединения к существующей сети перебрал расчёт. */
  tieInCandidates?: number
  millis: number
}

export interface Job {
  id: string
  datasetId: string
  status: JobStatus
  progress: number
  stage?: string
  createdAt: string
  startedAt?: string
  finishedAt?: string
  durationMillis?: number
  errorMessage?: string
  stats?: JobStats
  variants: Variant[]
}

/** Типы объектов выходного GeoJSON по разделу 7.1 технического приложения. */
export type ResultObjectType =
  | 'heat_network'
  | 'heat_chamber'
  | 'technical_node'
  | 'variant_summary'

/**
 * Новый участок сети. Вертикальное положение задаётся только глубинами начала
 * и конца: Z-координат в геометрии нет, в двумерном режиме глубины приходят null.
 */
export interface ResultSegmentProps {
  id: string
  object_type: 'heat_network'
  variant_id: string
  start_node_id: string
  end_node_id: string
  flow_tph: number
  diameter: number
  length: number
  laying_method: 'base' | 'special'
  depth_start: number | null
  depth_end: number | null
  cost: number
}

/**
 * Новая тепловая камера. Свойство `degree` сверх обязательного состава: по нему
 * видно, сколько участков к камере примыкает, а предел по приложению — четыре.
 */
export interface ResultChamberProps {
  id: string
  object_type: 'heat_chamber'
  variant_id: string
  diameter: number
  degree?: number
  cost: number
}

export interface ApiErrorBody {
  status: number
  error: string
  message: string
  path: string
  timestamp: string
  details?: string[]
}
