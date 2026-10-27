import type { ReactNode } from 'react'
import { api } from '../../api'
import { NumberInput, useLoad } from '../../components/ui'

/** One row of a weighted item table: an item spec, how many of it, how likely (relative weight). */
export interface LootRow {
  item: string
  min: number
  max: number
  weight: number
}

/** Rows of a weighted item table in mode settings (`[{item, min, max, weight}]`). */
export function readLootRows(value: unknown): LootRow[] {
  const rows = Array.isArray(value) ? (value as Record<string, unknown>[]) : []
  return rows.map((r) => ({
    item: String(r.item ?? ''),
    min: Number(r.min ?? 1),
    max: Number(r.max ?? 1),
    weight: Number(r.weight ?? 10),
  }))
}

/**
 * A weighted item table (e.g. the island's loot, 幸运高柱's items): one row per item, with how many come together and a
 * relative weight (chance = weight / total).
 */
export function LootTableField({
  loot,
  onChange,
  title = '物资表',
  emptyText = '物资表为空：岛上不会刷出任何物品。',
  hint,
}: {
  loot: LootRow[]
  onChange: (loot: LootRow[]) => void
  title?: string
  emptyText?: string
  /** how the table is drawn from, shown under it */
  hint: ReactNode
}) {
  const items = useLoad(api.itemRegistry)
  const total = loot.reduce((sum, r) => sum + Math.max(0, r.weight), 0)
  const setRow = (index: number, patch: Partial<LootRow>) => onChange(loot.map((r, i) => (i === index ? { ...r, ...patch } : r)))

  return (
    <div>
      <div className="row between wrap">
        <div className="settings-title">
          {title}（{loot.length}）
        </div>
        <button type="button" className="btn" onClick={() => onChange([...loot, { item: '', min: 1, max: 1, weight: 10 }])}>
          + 添加物品
        </button>
      </div>
      <datalist id="loot-item-ids">
        {(items.data ?? []).map((id) => (
          <option key={id} value={id} />
        ))}
      </datalist>
      {loot.length === 0 && <div className="text-warn small">{emptyText}</div>}
      <div className="table-scroll">
        <table className="table table-compact">
          <thead>
            <tr>
              <th>物品</th>
              <th>最少</th>
              <th>最多</th>
              <th>权重</th>
              <th>概率</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {loot.map((r, i) => (
              <tr key={i}>
                <td>
                  <input className="mono" list="loot-item-ids" value={r.item} placeholder="blockland:ak47" onChange={(e) => setRow(i, { item: e.target.value })} />
                </td>
                <td><NumberInput className="num" value={r.min} min={1} max={64} onChange={(v) => setRow(i, { min: Math.round(v) })} /></td>
                <td><NumberInput className="num" value={r.max} min={1} max={64} onChange={(v) => setRow(i, { max: Math.round(v) })} /></td>
                <td><NumberInput className="num" value={r.weight} min={1} max={1000} onChange={(v) => setRow(i, { weight: Math.round(v) })} /></td>
                <td className="muted small">{total > 0 ? `${((100 * Math.max(0, r.weight)) / total).toFixed(1)}%` : '-'}</td>
                <td>
                  <button type="button" className="icon-btn" aria-label="删除物品" onClick={() => onChange(loot.filter((_, j) => j !== i))}>
                    ×
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small">
        {hint}物品支持组件写法，如 <code>{'minecraft:bow[enchantments={power:1}]'}</code>。
      </p>
    </div>
  )
}
