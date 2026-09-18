import type { Job, Variant } from '../api/types'
import { duration, meters, moneyShort, money, plural, score } from '../lib/format'
import { Badge, Button, Empty, Progress, Row, Section } from './ui'

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
 * все шесть составляющих стоимости и показано, из чего складывается показатель S.
 */
export function VariantsPanel({
  job, activeVariant, onSelectVariant, onExport, onExportStatement,
}: Props) {
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
        <p className="text-[13px] text-tie">{job.errorMessage}</p>
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
              title="Перечень участков, камер, врезок и реконструкции со сводом
                     по диаметрам и итогом — таблица для Excel"
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
          title="ОКС без автоматического маршрута"
          hint="Раздел 2.9 ТЗ: построенная часть результата сохраняется, за каждый
                неподключённый объект начисляется штраф"
        >
          {job.variants
            .filter((v) => v.summary.unconnectedOksIds.length > 0)
            .map((v) => (
              <div key={v.variantCode} className="mb-2">
                <p className="mb-1 text-[11px] uppercase tracking-wide text-muted">
                  Вариант № {v.summary.rank}
                </p>
                <div className="flex flex-wrap gap-1">
                  {v.summary.unconnectedOksIds.map((id) => (
                    <Badge key={id} tone="bad">{id}</Badge>
                  ))}
                </div>
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
          <Row label="Кандидатов точек врезки" value={job.stats.tieInCandidates} mono />
        </Section>
      )}
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
        <Badge>{meters(s.length)}</Badge>
        <Badge>{plural(variant.featureCounts?.heat_network ?? 0, 'участок', 'участка', 'участков')}</Badge>
        <Badge>{plural(variant.featureCounts?.tie_in ?? 0, 'врезка', 'врезки', 'врезок')}</Badge>
        <Badge>{plural(variant.featureCounts?.heat_chamber ?? 0, 'камера', 'камеры', 'камер')}</Badge>
        {s.unconnectedOksIds.length > 0 && (
          <Badge tone="bad">не подключено: {s.unconnectedOksIds.length}</Badge>
        )}
        {(variant.featureCounts?.depth_crossing ?? 0) > 0 && (
          <Badge tone="info">
            {plural(variant.featureCounts?.depth_crossing ?? 0,
              'пересечение по глубине', 'пересечения по глубине', 'пересечений по глубине')}
          </Badge>
        )}
        {delta > 0 && <Badge tone="warn">+{moneyShort(delta)} к лучшему</Badge>}
      </div>

      {active && (
        <div className="mt-3 border-t border-edge pt-2">
          <p className="mb-1 text-[11px] uppercase tracking-wide text-muted">Из чего сложилась стоимость</p>
          <Row label="Новые участки сети" value={money(s.constructionCost)} mono />
          <Row label="Новые тепловые камеры" value={money(s.chamberConstructionCost)} mono />
          <Row label="Врезки" value={money(s.tieInCost)} mono />
          <Row label="Реконструкция участков" value={money(s.reconstructionCost)} mono />
          <Row label="Реконструкция камер" value={money(s.chamberReconstructionCost)} mono />
          {s.unconnectedPenalty > 0 && (
            <Row label="Штраф за неподключенные ОКС" value={money(s.unconnectedPenalty)} mono />
          )}
          <div className="mt-1 border-t border-edge pt-1">
            <Row label="Итого" value={money(s.calculatedCost)} mono accent />
          </div>

          <p className="mt-3 mb-1 text-[11px] uppercase tracking-wide text-muted">Протяжённость работ</p>
          <Row label="Новая сеть" value={meters(s.newNetworkLength)} mono />
          <Row label="Реконструкция" value={meters(s.reconstructionLength)} mono />
          <Row label="Всего" value={meters(s.length)} mono accent />

          <p className="mt-3 rounded bg-ink/60 px-2 py-1.5 font-mono text-[11px] leading-relaxed text-muted">
            S = 0,7 · {Math.round(s.calculatedCost).toLocaleString('ru-RU')} / 25 000 000
            {' + '}0,3 · {s.length.toLocaleString('ru-RU')} / 100 = {score(s.score)}
          </p>
        </div>
      )}
    </button>
  )
}
