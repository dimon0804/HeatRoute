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

export interface VariantSummary {
  rank: number
  constructionCost: number
  chamberConstructionCost: number
  tieInCost: number
  reconstructionCost: number
  chamberReconstructionCost: number
  unconnectedPenalty: number
  calculatedCost: number
  newNetworkLength: number
  reconstructionLength: number
  length: number
  score: number
  unconnectedOksIds: string[]
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
  tieInCandidates: number
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

/** Типы объектов выходного GeoJSON по разделу 10 технического приложения. */
export type ResultObjectType =
  | 'heat_network'
  | 'tie_in'
  | 'heat_network_reconstruction'
  | 'heat_chamber'
  | 'heat_chamber_reconstruction'
  | 'technical_node'
  | 'depth_crossing'
  | 'variant_summary'

/** Пересечение с существующей коммуникацией по глубине (дополнительная задача). */
export interface DepthCrossingProps {
  id: string
  object_type: 'depth_crossing'
  variant_id: string
  segment_id: string
  /** Расстояние от начала участка до пересечения, м. */
  station: number
  utility_id: string
  utility_type: string
  passage: 'above' | 'below'
  new_depth: number
  utility_depth: number
  required_clearance: number
  actual_clearance: number
}

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

export interface ResultReconstructionProps {
  id: string
  object_type: 'heat_network_reconstruction'
  variant_id: string
  existing_object_id: string
  existing_flow_tph: number
  added_flow_tph: number
  calculated_flow_tph: number
  existing_diameter: number
  required_diameter: number
  length: number
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
