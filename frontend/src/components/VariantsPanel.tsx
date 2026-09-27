import { useState } from 'react'
import { api } from '../api/client'
import type { Job, SensitivityReport, Variant } from '../api/types'
import { duration, meters, moneyShort, money, plural, score } from '../lib/format'
import { Badge, Button, Empty, Progress, Row, Section } from './ui'

/**
 * Сколько точек подключения проверяет анализ чувствительности из интерфейса.
 * Один прогон — это полный пересчёт задачи, около двадцати секунд; по всем
 * семнадцати точкам вышло бы минут пять, и столько никто перед экраном не ждёт.
 * Точки идут от самой тяжёлой по расходу, поэтому первые три — самые интересные.
 */
const SENSITIVITY_POINTS = 3

interface Props {
  job: Job | null
  activeVariant: string | null
  onSelectVariant: (code: string | null) => void
  onExport: () => void
  /** Ведомость объёмов работ по выбранному варианту. */
  onExportStatement: () => void
}

/**
 * Варианты подключения и разбор их стоимости.
 * <p>
 * Раздел 2.8 ТЗ требует показать содержательно разные варианты и объяснить их
 * ранжирование. Поэтому здесь не просто список: у каждого варианта расписаны
 * три составляющие стоимости строительства и показано, из чего складывается
 * показатель S.
 */
export function VariantsPanel({
  job, activeVariant, onSelectVariant, onExport, onExportStatement,
}: Props) {
  const [sensitivity, setSensitivity] = useState<SensitivityReport | null>(null)
  const [sensitivityRunning, setSensitivityRunning] = useState(false)
  const [sensitivityError, setSensitivityError] = useState<string | null>(null)

  async function runSensitivity(jobId: string) {
    setSensitivityRunning(true)
    setSensitivityError(null)
    try {
      setSensitivity(await api.sensitivity(jobId, SENSITIVITY_POINTS))
    } catch (error) {
      setSensitivityError(error instanceof Error ? error.message : String(error))
    } finally {
      setSensitivityRunning(false)
    }
  }

  if (!job) {
    return (
      <Section title="Варианты подключения">
        <Empty>Запустите расчёт — здесь появятся варианты.</Empty>
      </Section>
    )
  }

  if (job.status === 'QUEUED' || job.status === 'RUNNING') {
    return (
      <Section title="Расчёт идёт">
        <Progress value={job.progress} label={job.stage ?? 'Подготовка'} />
      </Section>
    )
  }

  if (job.status === 'FAILED') {
    return (
      <Section title="Расчёт прерван">
        <p className="text-[13px] text-alert">{job.errorMessage}</p>
      </Section>
    )
  }

  const best = job.variants[0]

  return (
    <>
      <Section
        title="Варианты подключения"
        hint="Чем меньше показатель S, тем выше вариант"
        stackRight
        right={(
          <div className="flex flex-col items-end gap-1.5">
            <Button variant="ghost" onClick={onExport}>Выгрузить результат в GeoJSON</Button>
            <Button
              variant="ghost"
              onClick={onExportStatement}
              title="Перечень новых участков, новых тепловых камер и врезок
                     в существующие камеры со сводом по диаметрам и итогом,
                     таблица для Excel"
            >
              Ведомость объёмов работ
            </Button>
          </div>
        )}
      >
        <div className="mb-3 flex items-center gap-2">
          <Button
            variant={activeVariant === null ? 'primary' : 'ghost'}
            onClick={() => onSelectVariant(null)}
          >
            Все
          </Button>
          {job.variants.map((variant) => (
            <Button
              key={variant.variantCode}
              variant={activeVariant === variant.variantCode ? 'primary' : 'ghost'}
              onClick={() => onSelectVariant(variant.variantCode)}
            >
              № {variant.summary.rank}
            </Button>
          ))}
        </div>

        <div className="space-y-2">
          {job.variants.map((variant) => (
            <VariantCard
              key={variant.variantCode}
              variant={variant}
              best={best}
              active={activeVariant === variant.variantCode}
              onSelect={() => onSelectVariant(variant.variantCode)}
            />
          ))}
        </div>
      </Section>

      {job.variants.some((v) => v.summary.unconnectedOksIds.length > 0) && (
        <Section
          title="Точки подключения без маршрута"
          hint="Раздел 2.9 ТЗ: построенная часть результата сохраняется, за каждую
                неподключённую точку начисляется штраф"
        >
          {job.variants
            .filter((v) => v.summary.unconnectedOksIds.length > 0)
            .map((v) => (
              <div key={v.variantCode} className="mb-2">
                <p className="mb-1 text-[11px] uppercase tracking-wide text-muted">
                  Вариант № {v.summary.rank}
                </p>
                <ul className="space-y-1.5">
                  {v.summary.unconnectedOksIds.map((id) => (
                    <li key={String(id)} className="rounded-md bg-ink/60 px-2.5 py-1.5">
                      <Badge tone="bad">{String(id)}</Badge>
                      {/* Список идентификаторов без объяснения бесполезен: раздел 2.9
                          требует обработать случай, а обработать — значит сказать,
                          что именно с точкой не так. */}
                      <p className="mt-1 text-[11.5px] leading-snug text-muted">
                        {v.summary.unconnectedReasons?.[String(id)] ?? 'Причина не определена'}
                      </p>
                    </li>
                  ))}
                </ul>
                <Row label="Штраф" value={money(v.summary.unconnectedPenalty)} mono />
              </div>
            ))}
        </Section>
      )}

      {job.stats && (
        <Section title="Как получен результат" hint="Показатели прогона — для вопросов о методе">
          <Row label="Время расчёта" value={duration(job.durationMillis ?? job.stats.millis)} mono />
          <Row label="ДУ для расчёта клиренсов" value={`${job.stats.designDiameter} мм`} mono />
          <Row label="Узлов в графе видимости" value={job.stats.graphNodes.toLocaleString('ru-RU')} mono />
          <Row label="Рёбер в графе" value={job.stats.graphEdges.toLocaleString('ru-RU')} mono />
          {job.stats.tieInCandidates != null && (
            <Row label="Кандидатов мест присоединения" value={job.stats.tieInCandidates} mono />
          )}
          {job.stats.sharpTurns != null && (
            <Row
              label="Поворотов круче предела"
              value={job.stats.sharpTurns}
              mono
              accent={job.stats.sharpTurns > 0}
            />
          )}
          {job.stats.verifiedMoves != null && (
            <Row
              label="Проверено ходов без улучшения"
              value={job.stats.verifiedMoves.toLocaleString('ru-RU')}
              mono
            />
          )}
        </Section>
      )}

      <Section
        title="Что держит цену"
        hint={`Задача решается заново без каждой точки подключения. `
          + `Проверяются ${SENSITIVITY_POINTS} самые тяжёлые точки, это около минуты`}
        stackRight
        right={(
          <Button
            variant="ghost"
            disabled={sensitivityRunning}
            onClick={() => void runSensitivity(job.id)}
          >
            {sensitivityRunning ? 'Считаю…' : 'Посчитать'}
          </Button>
        )}
      >
        {sensitivityError && (
          <p className="text-[12.5px] text-alert">{sensitivityError}</p>
        )}
        {!sensitivityError && !sensitivity && (
          <Empty>
            Вклад точки — это не длина отвода к ней. Убрав точку, расчёт
            перестраивает дерево целиком, и разница выходит другой.
          </Empty>
        )}
        {sensitivity && (
          <div className="space-y-1.5">
            {sensitivity.points.map((row) => (
              <div key={row.objectId} className="border-t border-edge/60 pt-1.5 first:border-0">
                <Row
                  label={`Без точки ${row.objectId}`}
                  value={`дешевле на ${moneyShort(row.costContribution)}`}
                  mono
                  accent
                />
                <p className="text-[11.5px] leading-snug text-muted">
                  {`Сеть короче на ${meters(row.lengthContribution)}, `}
                  {`показатель падает до ${score(row.score)}`}
                </p>
              </div>
            ))}
            <p className="pt-1 text-[11.5px] leading-snug text-muted">
              {`${plural(sensitivity.runs, 'прогон', 'прогона', 'прогонов')} `}
              {`за ${duration(sensitivity.millis)}. Исходный показатель `}
              {`${score(sensitivity.baseScore)}`}
            </p>
          </div>
        )}
      </Section>
    </>
  )
}

function VariantCard({ variant, best, active, onSelect }: {
  variant: Variant
  best: Variant | undefined
  active: boolean
  onSelect: () => void
}) {
  const s = variant.summary
  const delta = best && best.variantCode !== variant.variantCode
    ? s.calculatedCost - best.summary.calculatedCost
    : 0

  // Стоимость новых участков в сводке отдельным полем не передаётся: она равна
  // стоимости строительства без камер и без врезок в существующие камеры.
  const newSegmentsCost = s.constructionCost - s.chamberConstructionCost
    - s.existingChamberTieInCost
  // Слагаемые показателя S — чтобы разбор сходился с формулой построчно.
  const costTerm = 0.7 * (s.calculatedCost / 25_000_000)
  const lengthTerm = 0.3 * (s.newNetworkLength / 100)

  return (
    <button
      type="button"
      onClick={onSelect}
      className={
        'w-full rounded-lg border px-3 py-3 text-left transition-colors ' +
        (active ? 'border-accent/60 bg-accent/10' : 'border-edge hover:bg-edge/40')
      }
    >
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className={
            'flex h-6 w-6 items-center justify-center rounded-full text-[12px] font-bold ' +
            (s.rank === 1 ? 'bg-accent text-ink' : 'bg-edge text-slate-300')
          }>
            {s.rank}
          </span>
          <span className="font-mono text-[12px] text-muted">{variant.variantCode}</span>
        </div>
        <span className="font-mono text-[15px] font-semibold text-accent">S = {score(s.score)}</span>
      </div>

      <p className="mt-2 text-[12.5px] leading-snug text-slate-300">{variant.description}</p>

      <div className="mt-2 flex flex-wrap gap-1">
        <Badge tone="info">{moneyShort(s.calculatedCost)}</Badge>
        <Badge>{meters(s.newNetworkLength)}</Badge>
        <Badge>{plural(variant.featureCounts?.heat_network ?? 0, 'участок', 'участка', 'участков')}</Badge>
        <Badge>{plural(variant.featureCounts?.heat_chamber ?? 0, 'камера', 'камеры', 'камер')}</Badge>
        <Badge>{plural(s.existingChamberTieInCount, 'врезка', 'врезки', 'врезок')}</Badge>
        {s.unconnectedOksIds.length > 0 && (
          <Badge tone="bad">не подключено: {s.unconnectedOksIds.length}</Badge>
        )}
        {/* Пересечения по глубине в выгрузку не входят, приложение таких объектов
            не предусматривает, но расчёт их знает и отдаёт в подсчётах. По этому
            числу видно, у какого варианта профиль по глубине содержателен. */}
        {(variant.featureCounts?.depth_crossing ?? 0) > 0 && (
          <Badge tone="info">
            {plural(variant.featureCounts.depth_crossing,
              'пересечение по глубине', 'пересечения по глубине', 'пересечений по глубине')}
          </Badge>
        )}
        {delta > 0 && <Badge tone="warn">+{moneyShort(delta)} к лучшему</Badge>}
      </div>

      {active && (
        <div className="mt-3 border-t border-edge pt-2">
          <p className="mb-1 text-[11px] uppercase tracking-wide text-muted">Из чего сложилась стоимость</p>
          {/* Три слагаемых стоимости строительства. Первое приходит не отдельным
              полем, а остатком: сводка передаёт стоимость строительства целиком
              и выделяет из неё камеры и врезки. */}
          <Row label="Новые участки сети" value={money(newSegmentsCost)} mono />
          <Row label="Новые тепловые камеры" value={money(s.chamberConstructionCost)} mono />
          <Row
            label={`Врезки в существующие камеры, ${s.existingChamberTieInCount}`}
            value={money(s.existingChamberTieInCost)}
            mono
          />
          <div className="mt-1 border-t border-edge pt-1">
            <Row label="Стоимость строительства" value={money(s.constructionCost)} mono />
          </div>
          {s.unconnectedPenalty > 0 && (
            <Row label="Штраф за неподключённые точки" value={money(s.unconnectedPenalty)} mono />
          )}
          <div className="mt-1 border-t border-edge pt-1">
            <Row label="Итого" value={money(s.calculatedCost)} mono accent />
          </div>

          <p className="mt-3 mb-1 text-[11px] uppercase tracking-wide text-muted">Показатель варианта</p>
          <Row label="Новая сеть" value={meters(s.newNetworkLength)} mono />
          <Row label="Вклад стоимости, 0,7 · C / 25 000 000" value={score(costTerm)} mono />
          <Row label="Вклад длины, 0,3 · L / 100" value={score(lengthTerm)} mono />
          <Row label="S" value={score(s.score)} mono accent />

          <p className="mt-3 rounded bg-ink/60 px-2 py-1.5 font-mono text-[11px] leading-relaxed text-muted">
            S = 0,7 · {Math.round(s.calculatedCost).toLocaleString('ru-RU')} / 25 000 000
            {' + '}0,3 · {s.newNetworkLength.toLocaleString('ru-RU', { maximumFractionDigits: 1 })}
            {' / '}100 = {score(s.score)}
          </p>
        </div>
      )}
    </button>
  )
}
