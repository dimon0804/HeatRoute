import { useMemo, useState } from 'react'
import { flow, meters, money } from '../lib/format'
import { Badge, Empty, Section } from './ui'

interface Props {
  result: GeoJSON.FeatureCollection | null
  activeVariant: string | null
}

type Tab = 'segments' | 'reconstruction'

/**
 * Таблицы участков и реконструкции.
 * <p>
 * Пункты 4 и 6 сценария демонстрации (раздел 4 ТЗ) требуют показать расходы,
 * условные диаметры и участки, которым нужно увеличение диаметра. Карта отвечает
 * на вопрос «где», таблица — на вопрос «сколько».
 */
export function SegmentsPanel({ result, activeVariant }: Props) {
  const [tab, setTab] = useState<Tab>('segments')

  const rows = useMemo(() => {
    if (!result) return { segments: [], reconstruction: [] }
    const match = (props: Record<string, unknown>) =>
      !activeVariant || props.variant_id === activeVariant

    const segments = result.features
      .filter((f) => f.properties?.object_type === 'heat_network' && match(f.properties ?? {}))
      .map((f) => f.properties as Record<string, unknown>)
      .sort((a, b) => Number(b.flow_tph) - Number(a.flow_tph))

    const reconstruction = result.features
      .filter((f) => f.properties?.object_type === 'heat_network_reconstruction' && match(f.properties ?? {}))
      .map((f) => f.properties as Record<string, unknown>)
      .sort((a, b) => Number(b.calculated_flow_tph) - Number(a.calculated_flow_tph))

    return { segments, reconstruction }
  }, [result, activeVariant])

  if (!result) {
    return (
      <Section title="Участки">
        <Empty>Здесь появятся участки построенной сети.</Empty>
      </Section>
    )
  }

  return (
    <Section
      title={tab === 'segments' ? 'Участки новой сети' : 'Реконструкция существующей сети'}
      hint={
        tab === 'segments'
          ? 'Расход, условный диаметр, способ прокладки и стоимость'
          : 'Участки, которым после подключения не хватает пропускной способности'
      }
      right={
        <div className="flex gap-1">
          <TabButton active={tab === 'segments'} onClick={() => setTab('segments')}>
            Новые ({rows.segments.length})
          </TabButton>
          <TabButton active={tab === 'reconstruction'} onClick={() => setTab('reconstruction')}>
            Реконструкция ({rows.reconstruction.length})
          </TabButton>
        </div>
      }
    >
      {tab === 'segments' ? (
        rows.segments.length === 0 ? (
          <Empty>Участков нет.</Empty>
        ) : (
          <div className="max-h-72 overflow-y-auto">
            <table className="w-full text-[12px]">
              <thead className="sticky top-0 bg-panel text-[11px] uppercase text-muted">
                <tr>
                  <th className="py-1 text-left font-medium">Участок</th>
                  <th className="py-1 text-right font-medium">Расход</th>
                  <th className="py-1 text-right font-medium">ДУ</th>
                  <th className="py-1 text-right font-medium">Длина</th>
                  <th className="py-1 text-right font-medium">Стоимость</th>
                </tr>
              </thead>
              <tbody className="font-mono">
                {rows.segments.map((row) => (
                  <tr key={String(row.id)} className="border-t border-edge/60">
                    <td className="py-1 pr-2">
                      <span className="text-slate-300">{String(row.id)}</span>
                      {row.laying_method === 'special' && (
                        <span className="ml-1"><Badge tone="warn">спец</Badge></span>
                      )}
                    </td>
                    <td className="py-1 text-right text-slate-200">{flow(Number(row.flow_tph))}</td>
                    <td className="py-1 text-right text-accent">{String(row.diameter)}</td>
                    <td className="py-1 text-right text-slate-200">{meters(Number(row.length))}</td>
                    <td className="py-1 text-right text-slate-400">{money(Number(row.cost))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      ) : rows.reconstruction.length === 0 ? (
        <Empty>Реконструкция существующей сети не требуется.</Empty>
      ) : (
        <div className="max-h-72 overflow-y-auto">
          <table className="w-full text-[12px]">
            <thead className="sticky top-0 bg-panel text-[11px] uppercase text-muted">
              <tr>
                <th className="py-1 text-left font-medium">Участок</th>
                <th className="py-1 text-right font-medium">Расход</th>
                <th className="py-1 text-right font-medium">ДУ</th>
                <th className="py-1 text-right font-medium">Длина</th>
                <th className="py-1 text-right font-medium">Стоимость</th>
              </tr>
            </thead>
            <tbody className="font-mono">
              {rows.reconstruction.map((row) => (
                <tr key={String(row.id)} className="border-t border-edge/60">
                  <td className="py-1 pr-2 text-slate-300">{String(row.existing_object_id)}</td>
                  <td className="py-1 text-right text-slate-200">
                    {flow(Number(row.existing_flow_tph))}
                    <span className="text-muted"> → </span>
                    {flow(Number(row.calculated_flow_tph))}
                  </td>
                  <td className="py-1 text-right">
                    <span className="text-muted">{String(row.existing_diameter)}</span>
                    <span className="text-muted"> → </span>
                    <span className="text-recon">{String(row.required_diameter)}</span>
                  </td>
                  <td className="py-1 text-right text-slate-200">{meters(Number(row.length))}</td>
                  <td className="py-1 text-right text-slate-400">{money(Number(row.cost))}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Section>
  )
}

function TabButton({ active, onClick, children }: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        'rounded px-2 py-1 text-[11px] transition-colors ' +
        (active ? 'bg-accent/15 text-accent' : 'text-muted hover:text-slate-200')
      }
    >
      {children}
    </button>
  )
}
