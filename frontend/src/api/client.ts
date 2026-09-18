import type { ApiErrorBody, Dataset, ForbiddenZone, Job } from './types'

/**
 * Базовый адрес API. В собранном виде запросы идут через тот же nginx, что раздаёт
 * интерфейс, в разработке — через прокси Vite. И там, и там путь один и тот же,
 * поэтому в коде нет ни условий, ни переменных окружения по месту вызова.
 */
const BASE = import.meta.env.VITE_API_BASE ?? '/api'

export class ApiError extends Error {
  readonly status: number
  readonly details?: string[]

  constructor(body: ApiErrorBody) {
    super(body.message)
    this.status = body.status
    this.details = body.details
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${BASE}${path}`, init)
  if (!response.ok) {
    let body: ApiErrorBody | undefined
    try {
      body = (await response.json()) as ApiErrorBody
    } catch {
      body = undefined
    }
    throw new ApiError(
      body ?? {
        status: response.status,
        error: response.statusText,
        message: `Запрос завершился с кодом ${response.status}`,
        path,
        timestamp: new Date().toISOString(),
      },
    )
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export const api = {
  /** Загрузка конкурсного набора. Разбор идёт на сервере при приёме файла. */
  async uploadDataset(file: File): Promise<Dataset> {
    const form = new FormData()
    form.append('file', file)
    return request<Dataset>('/v1/datasets', { method: 'POST', body: form })
  },

  listDatasets(): Promise<Dataset[]> {
    return request<Dataset[]>('/v1/datasets?size=50')
  },

  getDataset(id: string): Promise<Dataset> {
    return request<Dataset>(`/v1/datasets/${id}`)
  },

  deleteDataset(id: string): Promise<void> {
    return request<void>(`/v1/datasets/${id}`, { method: 'DELETE' })
  },

  /** Ссылка на исходный файл набора: карта читает его напрямую, минуя состояние. */
  datasetSourceUrl(id: string): string {
    return `${BASE}/v1/datasets/${id}/source.geojson`
  },

  /**
   * Запуск расчёта. Режим с глубиной — дополнительная задача кейса: подбирается
   * глубина каждого участка, пересечения с существующими коммуникациями решаются
   * проходом сверху или снизу, стоимость пересчитывается с коэффициентом по глубине.
   */
  submitJob(
    datasetId: string,
    withDepth = false,
    forbiddenZones: ForbiddenZone[] = [],
  ): Promise<Job> {
    return request<Job>('/v1/jobs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ datasetId, withDepth, forbiddenZones }),
    })
  },

  getJob(id: string): Promise<Job> {
    return request<Job>(`/v1/jobs/${id}`)
  },

  listJobs(): Promise<Job[]> {
    return request<Job[]>('/v1/jobs?size=50')
  },

  /** Ссылка на выгрузку результата — её же отдают жюри как результат работы сервиса. */
  resultUrl(jobId: string): string {
    return `${BASE}/v1/jobs/${jobId}/result.geojson`
  },

  /** Ведомость объёмов работ по варианту — таблица для Excel. */
  statementUrl(jobId: string, variantCode?: string | null): string {
    const suffix = variantCode ? `?variantCode=${encodeURIComponent(variantCode)}` : ''
    return `${BASE}/v1/jobs/${jobId}/statement.csv${suffix}`
  },

  /** Результат расчёта как GeoJSON — для отрисовки на карте. */
  async fetchResult(jobId: string): Promise<GeoJSON.FeatureCollection> {
    const response = await fetch(`${BASE}/v1/jobs/${jobId}/result.geojson`)
    if (!response.ok) {
      throw new Error(`Не удалось получить результат: ${response.status}`)
    }
    return (await response.json()) as GeoJSON.FeatureCollection
  },

  async fetchDatasetSource(id: string): Promise<GeoJSON.FeatureCollection> {
    const response = await fetch(`${BASE}/v1/datasets/${id}/source.geojson`)
    if (!response.ok) {
      throw new Error(`Не удалось получить исходный набор: ${response.status}`)
    }
    return (await response.json()) as GeoJSON.FeatureCollection
  },
}
