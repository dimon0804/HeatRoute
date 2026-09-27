import type {
  ApiErrorBody, ComplianceReport, Dataset, ForbiddenZone, Job,
  SegmentExplanation, SensitivityReport,
} from './types'

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
   * Запуск расчёта. Режим с глубиной — необязательная часть кейса: подбирается
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

  /**
   * Проверка сохранённого результата расчёта на соответствие приложению.
   * Сверяется тот же файл, который уходит заказчику, вместе с входным набором,
   * по которому он получен.
   */
  jobCompliance(jobId: string): Promise<ComplianceReport> {
    return request<ComplianceReport>(`/v1/jobs/${jobId}/compliance`)
  },

  /**
   * Проверка произвольной выгрузки: выходной GeoJSON вместе с входным набором.
   * Сервис ничего не знает о том, каким расчётом получен файл, поэтому проверить
   * можно и выгрузку подрядчика.
   */
  checkCompliance(result: File, dataset: File): Promise<ComplianceReport> {
    const form = new FormData()
    form.append('result', result)
    form.append('dataset', dataset)
    return request<ComplianceReport>('/v1/compliance', { method: 'POST', body: form })
  },

  /**
   * Разбор трассы: чем зажат каждый участок варианта и насколько свободно
   * он лежит. Считается по запросу, а не хранится: спрашивают его уже после
   * того, как результат увиден, и про отдельный участок.
   */
  explain(jobId: string, variantCode?: string | null): Promise<SegmentExplanation[]> {
    const suffix = variantCode ? `?variantCode=${encodeURIComponent(variantCode)}` : ''
    return request<SegmentExplanation[]>(`/v1/jobs/${jobId}/explain${suffix}`)
  },

  /**
   * Анализ чувствительности: вклад каждой точки подключения в стоимость.
   * Ручка решает задачу заново без каждой точки по очереди, поэтому идёт
   * десятки секунд, и число точек всегда ограничивается явно.
   */
  sensitivity(jobId: string, limit: number): Promise<SensitivityReport> {
    return request<SensitivityReport>(`/v1/jobs/${jobId}/sensitivity?limit=${limit}`)
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
