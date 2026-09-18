import { useMemo } from 'react'
import { buildLongProfile } from '../lib/profile'
import { COLORS } from '../lib/mapStyle'
import { meters } from '../lib/format'

/**
 * Нормативные величины раздела 7 технического приложения. Здесь они нужны
 * только как линии на чертеже; расчёт ведёт бэкенд по своему справочнику.
 */
const NORMAL_DEPTH = 3.0
const MIN_DEPTH = 0.7
const MAX_SLOPE = 0.1

interface Props {
  segments: Record<string, unknown>[]
  crossings: Record<string, unknown>[]
  focusSegmentId: string
}

/**
 * Продольный профиль магистрали, проходящей через выбранный участок.
 * <p>
 * Разрез рядом показывает одно пересечение крупно, профиль — весь путь трубы:
 * спуск, площадку под коммуникацией и возврат на нормальную глубину. На защите
 * он отвечает на вопрос о соблюдении предельного уклона не словами, а числом
 * и линией.
 */
export function DepthProfile({ segments, crossings, focusSegmentId }: Props) {
  const profile = useMemo(
    () => buildLongProfile(segments, crossings, focusSegmentId),
    [segments, crossings, focusSegmentId],
  )

  if (!profile) return null

  const width = 320
  const height = 150
  const padLeft = 26
  const padRight = 8
  const padTop = 16
  const padBottom = 20

  const depthSpan = Math.max(profile.maxDepth + 0.6, NORMAL_DEPTH + 0.6)
  const plotW = width - padLeft - padRight
  const plotH = height - padTop - padBottom

  const x = (d: number) =>
    padLeft + (profile.totalLength > 0 ? (d / profile.totalLength) * plotW : 0)
  const y = (depth: number) => padTop + (depth / depthSpan) * plotH

  const line = profile.points.map((p) => `${x(p.distance)},${y(p.depth)}`).join(' ')
  const slopeOk = profile.maxSlope <= MAX_SLOPE + 1e-9

  return (
    <div className="mt-3 rounded-lg border border-edge bg-ink/50 p-3">
      <p className="mb-2 text-[11px] uppercase tracking-wide text-muted">
        Продольный профиль магистрали
      </p>

      <svg viewBox={`0 0 ${width} ${height}`} className="w-full" role="img"
           aria-label="Продольный профиль трассы по глубине">
        {/* поверхность земли */}
        <line x1={padLeft} y1={y(0)} x2={width - padRight} y2={y(0)}
              stroke="#5b6f86" strokeWidth="1.5" />
        <text x="2" y={y(0) + 3} fill="#8ea0b5" fontSize="8">0 м</text>

        {/* нормальная и минимальная глубина */}
        <line x1={padLeft} y1={y(NORMAL_DEPTH)} x2={width - padRight} y2={y(NORMAL_DEPTH)}
              stroke="#3a4a5e" strokeWidth="1" strokeDasharray="4 3" />
        <text x="2" y={y(NORMAL_DEPTH) + 3} fill="#61748c" fontSize="8">
          {NORMAL_DEPTH.toFixed(1)}
        </text>
        <line x1={padLeft} y1={y(MIN_DEPTH)} x2={width - padRight} y2={y(MIN_DEPTH)}
              stroke="#3a4a5e" strokeWidth="1" strokeDasharray="1 3" />
        <text x="2" y={y(MIN_DEPTH) + 3} fill="#61748c" fontSize="8">
          {MIN_DEPTH.toFixed(1)}
        </text>

        {/* грунт под профилем — чтобы читалось, где верх, а где низ */}
        <polygon
          points={`${padLeft},${y(0)} ${line} ${width - padRight},${y(0)}`}
          fill={COLORS.proposed}
          opacity="0.08"
        />

        {/* сама труба */}
        <polyline points={line} fill="none" stroke={COLORS.proposed}
                  strokeWidth="2" strokeLinejoin="round" />

        {/* пересечения */}
        {profile.crossings.map((c) => (
          <g key={c.id}>
            <line x1={x(c.distance)} y1={y(0)} x2={x(c.distance)} y2={y(depthSpan) - 4}
                  stroke={COLORS.depthCrossing} strokeWidth="0.8" strokeDasharray="2 2"
                  opacity="0.7" />
            <circle cx={x(c.distance)} cy={y(c.utilityDepth)} r="3.5"
                    fill={COLORS.existingNetwork} stroke="#0b1118" strokeWidth="1" />
            <circle cx={x(c.distance)} cy={y(c.depth)} r="2.5"
                    fill={COLORS.depthCrossing} />
          </g>
        ))}

        {/* шкала длины */}
        <text x={padLeft} y={height - 6} fill="#61748c" fontSize="8">0</text>
        <text x={width - padRight} y={height - 6} fill="#61748c" fontSize="8"
              textAnchor="end">
          {meters(profile.totalLength)}
        </text>
      </svg>

      <p className="mt-1 text-[11px] leading-snug text-muted">
        Магистраль из {profile.segmentIds.length} участков, {meters(profile.totalLength)}.
        Глубина от {profile.minDepth.toFixed(1)} до {profile.maxDepth.toFixed(1)} м.
        Наибольший уклон{' '}
        <span className={slopeOk ? 'text-slate-200' : 'text-tie'}>
          {profile.maxSlope.toFixed(3)} м/м
        </span>{' '}
        при пределе {MAX_SLOPE.toFixed(2)} м/м.
        {profile.crossings.length > 0 && (
          <>
            {' '}Кружками отмечены пересечения:{' '}
            <span style={{ color: COLORS.existingNetwork }}>существующая коммуникация</span>
            {' '}и{' '}
            <span style={{ color: COLORS.depthCrossing }}>новая сеть</span>.
          </>
        )}
      </p>
    </div>
  )
}
