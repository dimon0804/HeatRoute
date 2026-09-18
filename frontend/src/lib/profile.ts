/**
 * Продольный профиль трассы по глубине.
 *
 * Разрез в точке пересечения объясняет одно место, но проверяющему нужен ответ
 * на другой вопрос: как труба приходит на эту глубину и возвращается обратно.
 * Дополнительная задача ограничивает уклон 0,10 м/м и требует площадки перед
 * пересечением — на профиле и то и другое видно целиком.
 *
 * Данных выгрузки для этого достаточно: участок хранит узлы своих концов,
 * а материализация идёт от точки врезки наружу, поэтому `start_node_id`
 * всегда обращён к источнику. Цепочка восстанавливается по совпадению узлов.
 */

export interface ProfilePoint {
  /** Расстояние от начала цепочки, м. */
  distance: number
  /** Глубина заложения до верха габарита, м. */
  depth: number
}

export interface ProfileCrossing {
  id: string
  distance: number
  depth: number
  passage: 'above' | 'below'
  utilityType: string
  utilityDepth: number
  actualClearance: number
}

export interface LongProfile {
  points: ProfilePoint[]
  crossings: ProfileCrossing[]
  /** Участки цепочки по порядку — для подсветки на карте. */
  segmentIds: string[]
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
  crossings: Props[],
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

  // Вверх до точки врезки.
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
  const chainCrossings: ProfileCrossing[] = []
  const crossingsBySegment = new Map<string, Props[]>()
  for (const c of crossings) {
    const key = String(c.segment_id)
    const list = crossingsBySegment.get(key)
    if (list) list.push(c)
    else crossingsBySegment.set(key, [c])
  }

  let distance = 0
  let maxSlope = 0
  for (const s of chain) {
    const length = num(s.length)
    const from = num(s.depth_start)
    const to = num(s.depth_end)
    if (points.length === 0) points.push({ distance, depth: from })
    if (length > 0) maxSlope = Math.max(maxSlope, Math.abs(to - from) / length)
    // Положение внутри участка берётся из выгрузки; середина — запасной вариант
    // для результатов, посчитанных до появления этого атрибута.
    for (const c of crossingsBySegment.get(String(s.id)) ?? []) {
      const station = c.station == null ? length / 2 : num(c.station)
      chainCrossings.push({
        id: String(c.id),
        distance: distance + Math.max(0, Math.min(length, station)),
        depth: num(c.new_depth),
        passage: c.passage === 'below' ? 'below' : 'above',
        utilityType: String(c.utility_type),
        utilityDepth: num(c.utility_depth),
        actualClearance: num(c.actual_clearance),
      })
    }
    distance += length
    points.push({ distance, depth: to })
  }

  if (points.length < 2) return null

  return {
    points,
    crossings: chainCrossings,
    segmentIds: chain.map((s) => String(s.id)),
    totalLength: distance,
    maxSlope,
    minDepth: Math.min(...points.map((p) => p.depth)),
    maxDepth: Math.max(...points.map((p) => p.depth)),
  }
}
