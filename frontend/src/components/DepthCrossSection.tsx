import { RESTRICTION_LABELS } from '../lib/format'
import { COLORS } from '../lib/mapStyle'

interface Props {
  crossing: Record<string, unknown>
}

/**
 * Разрез в месте пересечения по глубине.
 * <p>
 * Карта отвечает на вопрос «где», таблица — «сколько», а этот разрез — «как».
 * На защите он объясняет решение дополнительной задачи за секунду: видно поверхность
 * земли, существующую коммуникацию, новую сеть выше или ниже неё и вертикальный
 * просвет между габаритами.
 * <p>
 * Габариты труб не приходят в выгрузке отдельными полями, но восстанавливаются
 * из неё однозначно: просвет задан как расстояние между внешними границами,
 * поэтому высота габарита равна разнице отметок минус просвет.
 */
export function DepthCrossSection({ crossing }: Props) {
  const newDepth = Number(crossing.new_depth)
  const utilityDepth = Number(crossing.utility_depth)
  const actual = Number(crossing.actual_clearance)
  const required = Number(crossing.required_clearance)
  const above = crossing.passage === 'above'

  // Высота расчётного габарита той трубы, что лежит выше: разница отметок
  // за вычетом просвета.
  const upperHeight = Math.max(0.1, Math.abs(utilityDepth - newDepth) - actual)

  const newTop = newDepth
  const utilityTop = utilityDepth
  const newHeight = above ? upperHeight : 0.4
  const utilityHeight = above ? 0.4 : upperHeight

  const maxDepth = Math.max(newTop + newHeight, utilityTop + utilityHeight) + 0.8

  // Геометрия рисунка: 1 метр по глубине — столько-то пикселей.
  const width = 320
  const height = 168
  const top = 18
  const scale = (height - top - 16) / maxDepth
  const y = (depth: number) => top + depth * scale

  const label = RESTRICTION_LABELS[String(crossing.utility_type)]
    ?? String(crossing.utility_type)

  return (
    <div className="mt-3 rounded-lg border border-edge bg-ink/50 p-3">
      <p className="mb-2 text-[11px] uppercase tracking-wide text-muted">
        Разрез в месте пересечения
      </p>

      <svg viewBox={`0 0 ${width} ${height}`} className="w-full" role="img"
           aria-label={`Новая сеть проходит ${above ? 'выше' : 'ниже'} объекта ${label}`}>
        {/* поверхность земли */}
        <line x1="0" y1={y(0)} x2={width} y2={y(0)} stroke="#5b6f86" strokeWidth="1.5" />
        <text x="2" y={y(0) - 5} fill="#8ea0b5" fontSize="9">поверхность земли, Z = 0</text>
        {Array.from({ length: 16 }).map((_, i) => (
          <line
            key={i}
            x1={i * 21}
            y1={y(0)}
            x2={i * 21 - 6}
            y2={y(0) + 5}
            stroke="#3a4a5e"
            strokeWidth="1"
          />
        ))}

        {/* существующая коммуникация — в разрезе, поперёк */}
        <ellipse
          cx={width * 0.5}
          cy={y(utilityTop + utilityHeight / 2)}
          rx={utilityHeight * scale * 0.7 + 5}
          ry={(utilityHeight * scale) / 2 + 2}
          fill={COLORS.existingNetwork}
          stroke="#0b1118"
        />
        <text
          x={width * 0.5 + 24}
          y={y(utilityTop + utilityHeight / 2) + 3}
          fill="#8ea0b5"
          fontSize="9"
        >
          {label}, {utilityDepth.toFixed(1)} м
        </text>

        {/* новая сеть — вдоль разреза */}
        <rect
          x="16"
          y={y(newTop)}
          width={width - 32}
          height={Math.max(4, newHeight * scale)}
          rx="2"
          fill={COLORS.proposed}
          opacity="0.9"
        />
        <text x="18" y={y(newTop) - 4} fill={COLORS.proposed} fontSize="9">
          новая сеть, {newDepth.toFixed(1)} м
        </text>

        {/* вертикальный просвет */}
        <line
          x1={width * 0.5 - 40}
          y1={y(above ? newTop + newHeight : utilityTop + utilityHeight)}
          x2={width * 0.5 - 40}
          y2={y(above ? utilityTop : newTop)}
          stroke={COLORS.depthCrossing}
          strokeWidth="1.5"
          markerStart="url(#tick)"
          markerEnd="url(#tick)"
        />
        <text
          x={width * 0.5 - 36}
          y={y((newTop + utilityTop) / 2) + 3}
          fill={COLORS.depthCrossing}
          fontSize="9"
        >
          просвет {actual.toFixed(2)} м
        </text>

        <defs>
          <marker id="tick" markerWidth="6" markerHeight="6" refX="3" refY="3">
            <line x1="0" y1="3" x2="6" y2="3" stroke={COLORS.depthCrossing} strokeWidth="1.5" />
          </marker>
        </defs>
      </svg>

      <p className="mt-1 text-[11px] leading-snug text-muted">
        Новая сеть проходит <span className="text-slate-200">{above ? 'сверху' : 'снизу'}</span>.
        Просвет {actual.toFixed(2)} м при норме {required.toFixed(2)} м.
        {above
          ? ' Проход сверху выбран потому, что он выводит трассу выше трёх метров, где стоимость по глубине не растёт.'
          : ' Проход снизу выбран потому, что сверху не выдержать минимальную глубину.'}
      </p>
    </div>
  )
}
