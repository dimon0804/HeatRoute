import type { ForbiddenZone } from '../api/types'
import { Button } from './ui'

interface Props {
  zones: ForbiddenZone[]
  radiusM: number
  placing: boolean
  onRadiusChange: (radiusM: number) => void
  onTogglePlacing: () => void
  onRemove: (index: number) => void
  onClear: () => void
}

/**
 * Запретные зоны: «здесь копать нельзя».
 * <p>
 * Отвечает на вопрос, который возникает первым при обсуждении любой трассы. Стройплощадка,
 * охранная зона, участок, который город не отдаёт, — всё это появляется позже входных
 * данных и в них не описано. Зона задаётся щелчком по карте и работает как обычное
 * препятствие: трасса её обходит, а расчёт показывает, во что обход обошёлся.
 * <p>
 * Это не правка результата руками, запрещённая разделом 2.12 ТЗ, а дополнительное
 * исходное условие: геометрию по-прежнему строит расчёт, и целиком заново.
 */
export function ZonesPanel({
  zones, radiusM, placing, onRadiusChange, onTogglePlacing, onRemove, onClear,
}: Props) {
  return (
    <div className="border-t border-edge px-4 py-3">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-[13px] font-semibold uppercase tracking-wide text-slate-300">
            Запретные зоны
          </h2>
          <p className="mt-0.5 text-[11.5px] leading-snug text-muted">
            Где трассе проходить нельзя. Расчёт обойдёт их или честно покажет,
            что объект остался без маршрута
          </p>
        </div>
        <Button onClick={onTogglePlacing} variant={placing ? 'primary' : 'ghost'}>
          {placing ? 'Готово' : 'Указать'}
        </Button>
      </div>

      {placing && (
        <p className="mt-2 rounded-md bg-accent/10 px-2.5 py-1.5 text-[11.5px] text-accent">
          Щёлкните по карте, чтобы поставить зону
        </p>
      )}

      <label className="mt-3 flex items-center gap-2 text-[12px] text-muted">
        Радиус
        <input
          type="range"
          min={10}
          max={150}
          step={5}
          value={radiusM}
          onChange={(e) => onRadiusChange(Number(e.target.value))}
          className="flex-1 accent-accent"
        />
        <span className="w-14 text-right font-mono text-[12px] text-slate-200">
          {radiusM} м
        </span>
      </label>

      {zones.length > 0 && (
        <>
          <ul className="mt-2.5 space-y-1">
            {zones.map((zone, index) => (
              <li
                key={`${zone.lon}:${zone.lat}:${index}`}
                className="flex items-center justify-between rounded-md bg-ink/60 px-2.5 py-1.5"
              >
                <span className="font-mono text-[11.5px] text-slate-300">
                  № {index + 1} · R {zone.radiusM} м · {zone.lat.toFixed(5)}, {zone.lon.toFixed(5)}
                </span>
                <button
                  type="button"
                  onClick={() => onRemove(index)}
                  className="text-[11.5px] text-muted underline hover:text-tie"
                >
                  убрать
                </button>
              </li>
            ))}
          </ul>
          <div className="mt-2 flex items-center justify-between">
            <span className="text-[11.5px] text-muted">
              Зон: {zones.length}. Расчёт учтёт их при следующем запуске
            </span>
            <button
              type="button"
              onClick={onClear}
              className="text-[11.5px] text-muted underline hover:text-slate-200"
            >
              очистить
            </button>
          </div>
        </>
      )}
    </div>
  )
}
