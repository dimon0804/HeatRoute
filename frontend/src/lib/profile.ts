/**
 * Продольный профиль трассы по глубине.
 *
 * Отдельных объектов пересечения выгрузка больше не содержит: вертикальное
 * положение задано только глубинами начала и конца участка. Этого достаточно,
 * чтобы собрать профиль целиком и ответить на главный вопрос по режиму глубины —
 * как труба уходит вниз, на какой отметке идёт и выдержан ли предельный уклон.
 *
 * Данных выгрузки для этого хватает: участок хранит узлы своих концов,
 * а материализация идёт от места присоединения наружу, поэтому `start_node_id`
 * всегда обращён к источнику. Цепочка восстанавливается по совпадению узлов.
 */

export interface ProfilePoint {
  /** Расстояние от начала цепочки, м. */
  distance: number
  /** Глубина заложения до верха габарита, м. */
  depth: number
}

/** Один участок цепочки с его положением на профиле. */
export interface ProfileSegment {
  id: string
  /** Расстояние от начала цепочки до начала участка, м. */
  from: number
  /** Расстояние от начала цепочки до конца участка, м. */
  to: number
  depthStart: number
  depthEnd: number
  /** Уклон участка по абсолютной величине, м/м. */
  slope: number
}

export interface LongProfile {
  points: ProfilePoint[]
  /** Участки цепочки по порядку — для подсветки на карте и на чертеже. */
  segments: ProfileSegment[]
  totalLength: number
  /** Наибольший уклон профиля, м/м. */
  maxSlope: number
  minDepth: number
  maxDepth: number
}

type Props = Record<string, unknown>

const num = (v: unknown): number => Number(v)

/**
 * Собирает профиль магистрали, проходящей через заданный участок.
 *
 * Вверх цепочка единственна — у участка один вход. Вниз на развилке берётся
 * ветвь с наибольшим расходом: это и есть магистраль, отводы показываются
 * своими профилями при выборе их участков.
 */
export function buildLongProfile(
  segments: Props[],
  focusSegmentId: string,
): LongProfile | null {
  const byId = new Map<string, Props>()
  const byStart = new Map<string, Props[]>()
  const byEnd = new Map<string, Props>()
  for (const s of segments) {
    if (s.depth_start == null || s.depth_end == null) continue
    const id = String(s.id)
    byId.set(id, s)
    const start = String(s.start_node_id)
    const list = byStart.get(start)
    if (list) list.push(s)
    else byStart.set(start, [s])
    byEnd.set(String(s.end_node_id), s)
  }

  const focus = byId.get(focusSegmentId)
  if (!focus) return null

  // Вверх до места присоединения к существующей сети.
  const upstream: Props[] = []
  const seen = new Set<string>([focusSegmentId])
  let cursor = byEnd.get(String(focus.start_node_id))
  while (cursor && !seen.has(String(cursor.id))) {
    seen.add(String(cursor.id))
    upstream.unshift(cursor)
    cursor = byEnd.get(String(cursor.start_node_id))
  }

  // Вниз по магистрали.
  const downstream: Props[] = []
  let node = String(focus.end_node_id)
  while (true) {
    const next = (byStart.get(node) ?? [])
      .filter((s) => !seen.has(String(s.id)))
      .sort((a, b) => num(b.flow_tph) - num(a.flow_tph))[0]
    if (!next) break
    seen.add(String(next.id))
    downstream.push(next)
    node = String(next.end_node_id)
  }

  const chain = [...upstream, focus, ...downstream]

  const points: ProfilePoint[] = []
  const chainSegments: ProfileSegment[] = []

  let distance = 0
  let maxSlope = 0
  for (const s of chain) {
    const length = num(s.length)
    const from = num(s.depth_start)
    const to = num(s.depth_end)
    const slope = length > 0 ? Math.abs(to - from) / length : 0
    if (points.length === 0) points.push({ distance, depth: from })
    maxSlope = Math.max(maxSlope, slope)
    chainSegments.push({
      id: String(s.id),
      from: distance,
      to: distance + length,
      depthStart: from,
      depthEnd: to,
      slope,
    })
    distance += length
    points.push({ distance, depth: to })
  }

  if (points.length < 2) return null

  return {
    points,
    segments: chainSegments,
    totalLength: distance,
    maxSlope,
    minDepth: Math.min(...points.map((p) => p.depth)),
    maxDepth: Math.max(...points.map((p) => p.depth)),
  }
}
