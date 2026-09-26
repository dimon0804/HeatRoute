/** Форматирование чисел для показа на экране. Везде русская локаль и неразрывные пробелы. */

const RUB = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 })
const NUM1 = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 })
const NUM2 = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 })
const NUM3 = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 3 })

/** Рубли целиком: «348 401 073 ₽». */
export function money(value: number): string {
  return `${RUB.format(Math.round(value))} ₽`
}

/**
 * Рубли крупно: «348,4 млн ₽».
 * В сводках стоимость сравнивают глазами, и девять значащих цифр этому мешают.
 */
export function moneyShort(value: number): string {
  if (Math.abs(value) >= 1e9) {
    return `${NUM2.format(value / 1e9)} млрд ₽`
  }
  if (Math.abs(value) >= 1e6) {
    return `${NUM1.format(value / 1e6)} млн ₽`
  }
  if (Math.abs(value) >= 1e3) {
    return `${NUM1.format(value / 1e3)} тыс ₽`
  }
  return money(value)
}

export function meters(value: number): string {
  return `${NUM1.format(value)} м`
}

export function flow(value: number): string {
  return `${NUM2.format(value)} т/ч`
}

export function score(value: number): string {
  return NUM3.format(value)
}

export function bytes(value: number): string {
  if (value >= 1 << 30) return `${NUM2.format(value / (1 << 30))} ГБ`
  if (value >= 1 << 20) return `${NUM1.format(value / (1 << 20))} МБ`
  if (value >= 1 << 10) return `${NUM1.format(value / (1 << 10))} КБ`
  return `${value} Б`
}

export function duration(millis?: number | null): string {
  if (millis == null) return '—'
  if (millis < 1000) return `${millis} мс`
  const seconds = millis / 1000
  if (seconds < 60) return `${NUM1.format(seconds)} с`
  const minutes = Math.floor(seconds / 60)
  return `${minutes} мин ${Math.round(seconds % 60)} с`
}

export function dateTime(iso?: string): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** Склонение числительных: «17 объектов», «1 объект», «2 объекта». */
export function plural(count: number, one: string, few: string, many: string): string {
  const mod10 = count % 10
  const mod100 = count % 100
  if (mod10 === 1 && mod100 !== 11) return `${count} ${one}`
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return `${count} ${few}`
  return `${count} ${many}`
}

/** Человеческие названия типов объектов — их видно в легенде и таблицах. */
export const OBJECT_TYPE_LABELS: Record<string, string> = {
  source: 'Источник теплоснабжения',
  heat_network: 'Тепловая сеть',
  heat_chamber: 'Тепловая камера',
  oks_future: 'Перспективный ОКС',
  oks_connection_point: 'Точка подключения',
  oks_existing: 'Существующий ОКС',
  restriction: 'Пространственное ограничение',
  technical_node: 'Технический узел',
  variant_summary: 'Сводка варианта',
}

export const RESTRICTION_LABELS: Record<string, string> = {
  oks_existing: 'Существующие здания',
  park: 'Парк',
  social_area: 'Территория социального объекта',
  prohibited_site: 'Запрещённая территория',
  water: 'Водный объект',
  road: 'Автомобильная дорога',
  tram_tracks: 'Трамвайные пути',
  // Железная дорога стоит отдельной строкой таблицы ограничений: пересекать её нельзя.
  railway: 'Железная дорога',
  gas_pipeline: 'Газопровод',
  power_cable: 'Силовой кабель',
  heat_network: 'Существующая тепловая сеть',
}

export const SEVERITY_LABELS: Record<string, string> = {
  INFO: 'Сведения',
  ASSUMPTION: 'Допущение',
  WARNING: 'Предупреждение',
  ERROR: 'Ошибка',
}

export const JOB_STATUS_LABELS: Record<string, string> = {
  QUEUED: 'В очереди',
  RUNNING: 'Считается',
  COMPLETED: 'Готово',
  FAILED: 'Ошибка',
  CANCELLED: 'Отменён',
}
