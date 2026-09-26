import { useEffect, useMemo, useRef, useState } from 'react'
import clsx from 'clsx'
import type { ComplianceFinding, ComplianceReport, Job } from '../api/types'
import { integer, OBJECT_TYPE_LABELS, plural, pluralWord } from '../lib/format'
import { Badge, Button, Empty, Row, Section } from './ui'

interface Props {
  job: Job | null
  /** Отчёт по выгрузке текущего расчёта. */
  report: ComplianceReport | null
  loading: boolean
  error: string | null
  /** Отчёт по загруженным файлам: чужая выгрузка со своим входным набором. */
  uploaded: ComplianceReport | null
  checking: boolean
  uploadError: string | null
  onCheckFiles: (result: File, dataset: File) => void
  /** Показать объект на карте — тем же способом, каким это делает таблица участков. */
  onSelectFeature: (id: string | null) => void
  selectedFeatureId: string | null
  /** Переключить вариант: объект нарушения может относиться не к показанному. */
  onSelectVariant: (code: string) => void
}

/**
 * Проверка выгрузки на соответствие техническому приложению.
 * <p>
 * Панель существует не для поиска наших ошибок: их ловят тесты. Смысл в том, что
 * проверить можно любую выгрузку — свою, чужую, полученную другим сервисом.
 * Департамент принимает такие расчёты от подрядчиков и сверяет их глазами:
 * пересчитать стоимость, сверить диаметры с таблицей, обойти дерево и убедиться,
 * что предельная длина не превышена ни на одном пути. Здесь это одна загрузка файлов.
 */
export function CompliancePanel({
  job, report, loading, error,
  uploaded, checking, uploadError, onCheckFiles,
  onSelectFeature, selectedFeatureId, onSelectVariant,
}: Props) {
  const [resultFile, setResultFile] = useState<File | null>(null)
  const [datasetFile, setDatasetFile] = useState<File | null>(null)

  return (
    <>
      <Section
        title="Соответствие приложению"
        hint="Состав атрибутов, выбор диаметров, стоимость, связность сети и предельные
              длины сверяются с правилами технического приложения"
      >
        {!job ? (
          <Empty>Запустите расчёт — здесь появится проверка его выгрузки.</Empty>
        ) : job.status === 'QUEUED' || job.status === 'RUNNING' ? (
          <Empty>Расчёт идёт, выгрузка проверяется по его окончании.</Empty>
        ) : job.status !== 'COMPLETED' ? (
          <Empty>Расчёт не завершён, проверять нечего.</Empty>
        ) : loading ? (
          <p className="text-[13px] text-muted">Выгрузка проверяется…</p>
        ) : error ? (
          <p className="text-[13px] text-alert">{error}</p>
        ) : report ? (
          <ReportView
            report={report}
            onSelectObject={(finding, id) => {
              if (finding.variantId) onSelectVariant(finding.variantId)
              onSelectFeature(selectedFeatureId === id ? null : id)
            }}
            selectedFeatureId={selectedFeatureId}
          />
        ) : null}
      </Section>

      <Section
        title="Проверка чужой выгрузки"
        hint="Проверить можно любой файл, а не только расчёт этого сервиса. Подрядчик
              присылает выходной GeoJSON, проверяющий кладёт его сюда вместе с входным
              набором и получает перечень нарушений с указанием объектов"
      >
        <div className="space-y-2">
          <FilePick
            label="Выходной GeoJSON"
            hint="Файл, который проверяем"
            file={resultFile}
            onPick={setResultFile}
          />
          <FilePick
            label="Входной набор"
            hint="Набор, по которому получен результат"
            file={datasetFile}
            onPick={setDatasetFile}
          />
        </div>

        <div className="mt-3">
          <Button
            onClick={() => resultFile && datasetFile && onCheckFiles(resultFile, datasetFile)}
            disabled={!resultFile || !datasetFile || checking}
          >
            {checking ? 'Проверка…' : 'Проверить выгрузку'}
          </Button>
          {(!resultFile || !datasetFile) && (
            <p className="mt-1.5 text-[11px] leading-snug text-muted">
              Нужны оба файла: без входного набора часть правил не проверяется
            </p>
          )}
        </div>

        {uploadError && (
          <p className="mt-3 text-[12.5px] leading-snug text-alert">{uploadError}</p>
        )}

        {uploaded && (
          <div className="mt-3 border-t border-edge pt-3">
            <ReportView report={uploaded} />
          </div>
        )}
      </Section>
    </>
  )
}

// =====================================================================================

/** Отчёт как таблица: главная строка, состав выгрузки, нарушения, непроверенное. */
function ReportView({ report, onSelectObject, selectedFeatureId }: {
  report: ComplianceReport
  /** Задан только там, где объекты нарушения есть на карте. */
  onSelectObject?: (finding: ComplianceFinding, objectId: string) => void
  selectedFeatureId?: string | null
}) {
  const findings = report.findings ?? []
  const skipped = report.skipped ?? []
  // У выгрузки, где вариант не проставлен, идентификатор приходит пустым:
  // показывать «null» строкой вместо кода варианта незачем.
  const variantIds = (report.variantIds ?? []).filter(Boolean)
  const objectCounts = report.objectCounts ?? {}

  // Нарушения собираются по правилам: одно правило нарушается сразу на многих
  // объектах, и список из ста строк с повторяющейся формулировкой нечитаем.
  const groups = useMemo(() => {
    const byRule = new Map<string, ComplianceFinding[]>()
    findings.forEach((finding) => {
      const list = byRule.get(finding.rule)
      if (list) list.push(finding)
      else byRule.set(finding.rule, [finding])
    })
    return [...byRule.entries()].map(([rule, items]) => ({ rule, items }))
  }, [findings])

  // Первое правило раскрыто сразу: объекты и числа нужны читателю без лишнего щелчка.
  const [expanded, setExpanded] = useState<string | null>(() => groups[0]?.rule ?? null)
  useEffect(() => {
    setExpanded(groups[0]?.rule ?? null)
  }, [groups])

  const tone = report.checks === 0 ? 'unknown' : report.violations > 0 ? 'bad' : 'good'

  return (
    <>
      <div
        className={clsx(
          'rounded-lg border px-3 py-2.5',
          tone === 'good' && 'border-oks/40 bg-oks/10',
          tone === 'bad' && 'border-alert/40 bg-alert/10',
          tone === 'unknown' && 'border-warn/40 bg-warn/10',
        )}
      >
        <p className={clsx(
          'text-[14px] font-semibold leading-snug',
          tone === 'good' && 'text-oks',
          tone === 'bad' && 'text-alert',
          tone === 'unknown' && 'text-warn',
        )}>
          {tone === 'unknown'
            ? 'Проверка не выполнена'
            : tone === 'good'
              ? 'Нарушений не найдено'
              : `Найдено ${integer(report.violations)} `
                + pluralWord(report.violations, 'нарушение', 'нарушения', 'нарушений')}
        </p>
        <p className="mt-1 font-mono text-[12px] text-slate-200">
          {`Выполнено ${integer(report.checks)} `}
          {pluralWord(report.checks, 'сверка', 'сверки', 'сверок')}
        </p>
      </div>

      <p className="mt-2 text-[11px] leading-snug text-muted/80">
        Число сверок стоит рядом с числом нарушений намеренно. Само по себе
        «нарушений нет» ничего не говорит о выгрузке: сверок могло быть ноль.
      </p>

      {(variantIds.length > 0 || Object.keys(objectCounts).length > 0) && (
        <div className="mt-3 border-t border-edge pt-2">
          <p className="mb-1 text-[11px] uppercase tracking-wide text-muted">
            Что проверено
          </p>
          {variantIds.length > 0 && (
            <div className="mb-1.5 flex flex-wrap items-baseline gap-1">
              <span className="text-[13px] text-muted">
                {plural(variantIds.length, 'вариант', 'варианта', 'вариантов')}:
              </span>
              {variantIds.map((id) => (
                <span key={id} className="font-mono text-[12px] text-slate-200">{id}</span>
              ))}
            </div>
          )}
          {Object.entries(objectCounts)
            .sort((a, b) => b[1] - a[1])
            .map(([type, count]) => (
              <Row key={type} label={OBJECT_TYPE_LABELS[type] ?? type} value={count} />
            ))}
        </div>
      )}

      {groups.length > 0 && (
        <div className="mt-3 border-t border-edge pt-2">
          <p className="mb-1.5 text-[11px] uppercase tracking-wide text-muted">
            Нарушенные правила
          </p>
          <ul className="space-y-1.5">
            {groups.map((group) => {
              const first = group.items[0]
              const open = expanded === group.rule
              return (
                <li
                  key={group.rule}
                  className="rounded-md border border-alert/30 bg-alert/5 px-3 py-2"
                >
                  <div className="flex items-start justify-between gap-2">
                    <p className="text-[12.5px] font-medium leading-snug text-slate-200">
                      {first.title}
                    </p>
                    <span className="shrink-0"><Badge tone="bad">{group.items.length}</Badge></span>
                  </div>
                  <p className="mt-1 text-[11.5px] leading-snug text-muted">
                    {first.requirement}
                  </p>
                  <div className="mt-1 flex items-center gap-2">
                    <code className="text-[10.5px] text-muted/80">{group.rule}</code>
                    <button
                      type="button"
                      className="text-[11px] text-accent hover:underline"
                      onClick={() => setExpanded(open ? null : group.rule)}
                    >
                      {open ? 'скрыть подробности' : 'показать подробности'}
                    </button>
                  </div>

                  {open && (
                    <ul className="mt-1.5 max-h-52 space-y-1.5 overflow-y-auto pr-1">
                      {group.items.map((finding, index) => (
                        <li
                          key={`${finding.variantId ?? ''}:${finding.objectIds.join(',')}:${index}`}
                          className="rounded bg-ink/60 px-2 py-1.5"
                        >
                          <p className="text-[11.5px] leading-snug text-slate-300">
                            {finding.detail}
                          </p>
                          <div className="mt-1 flex flex-wrap items-center gap-1">
                            {finding.variantId && (
                              <span className="font-mono text-[10.5px] text-muted">
                                {finding.variantId}
                              </span>
                            )}
                            {finding.objectIds.map((id) => (
                              onSelectObject ? (
                                <button
                                  key={id}
                                  type="button"
                                  title="Показать объект на карте"
                                  onClick={() => onSelectObject(finding, id)}
                                  className={
                                    'rounded px-1.5 py-0.5 font-mono text-[10.5px] transition-colors '
                                    + (selectedFeatureId === id
                                      ? 'bg-accent/20 text-accent'
                                      : 'bg-edge/60 text-slate-300 hover:bg-edge')
                                  }
                                >
                                  {id}
                                </button>
                              ) : (
                                <span
                                  key={id}
                                  className="rounded bg-edge/40 px-1.5 py-0.5 font-mono text-[10.5px] text-muted"
                                >
                                  {id}
                                </span>
                              )
                            ))}
                          </div>
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              )
            })}
          </ul>
        </div>
      )}

      {skipped.length > 0 && (
        <div className="mt-3 border-t border-edge pt-2">
          <p className="mb-1 text-[11px] uppercase tracking-wide text-muted">
            Не проверялось
          </p>
          <ul className="space-y-1">
            {skipped.map((line) => (
              <li key={line} className="text-[11.5px] leading-snug text-muted">{line}</li>
            ))}
          </ul>
        </div>
      )}
    </>
  )
}

/** Выбор одного файла: имя выбранного видно сразу, иначе проверять непонятно что. */
function FilePick({ label, hint, file, onPick }: {
  label: string
  hint: string
  file: File | null
  onPick: (file: File) => void
}) {
  const input = useRef<HTMLInputElement>(null)

  return (
    <div className="rounded-md border border-edge bg-ink/40 px-3 py-2">
      <div className="flex items-center justify-between gap-2">
        <div className="min-w-0">
          <p className="text-[12.5px] text-slate-200">{label}</p>
          <p className="text-[11px] leading-snug text-muted/80">{hint}</p>
        </div>
        <Button variant="ghost" onClick={() => input.current?.click()}>
          {file ? 'Заменить' : 'Выбрать'}
        </Button>
      </div>
      <input
        ref={input}
        type="file"
        accept=".geojson,.json,application/geo+json,application/json"
        className="hidden"
        onChange={(event) => {
          const picked = event.target.files?.[0]
          if (picked) onPick(picked)
          event.target.value = ''
        }}
      />
      {file && (
        <p className="mt-1 truncate font-mono text-[11px] text-accent" title={file.name}>
          {file.name}
        </p>
      )}
    </div>
  )
}
