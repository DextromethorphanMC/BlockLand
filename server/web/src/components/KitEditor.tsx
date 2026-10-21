import { useState } from 'react'
import type { KitItem } from '../types'
import { Field, NumberInput } from './ui'

const ARMOR: { slot: string; label: string }[] = [
  { slot: 'head', label: '头盔' },
  { slot: 'chest', label: '胸甲' },
  { slot: 'legs', label: '护腿' },
  { slot: 'feet', label: '靴子' },
]
const MAIN_ROWS = [0, 1, 2].map((row) => Array.from({ length: 9 }, (_, col) => String(9 + row * 9 + col)))
const HOTBAR = Array.from({ length: 9 }, (_, i) => String(i))

export function slotLabel(slot: string) {
  const armor = ARMOR.find((a) => a.slot === slot)
  if (armor) return armor.label
  if (slot === 'offhand') return '副手'
  const index = Number(slot)
  return index < 9 ? `快捷栏 ${index + 1}` : `背包 ${index - 8}`
}

function shortName(item: string) {
  const base = item.split('[')[0]
  return base.includes(':') ? base.split(':')[1] : base
}

/**
 * Starting kit ("开局装备") laid out like the player inventory. Generic: shown for every game type with
 * usesKit. Armour stays where the admin puts it; players can rearrange the rest in the client.
 */
export function KitEditor({ kit, onChange, items }: { kit: KitItem[]; onChange: (kit: KitItem[]) => void; items: string[] }) {
  const [selected, setSelected] = useState<string>('0')
  const at = (slot: string) => kit.find((k) => k.slot === slot)
  const current = at(selected)

  const put = (patch: Partial<KitItem>) => {
    if (current) onChange(kit.map((k) => (k.slot === selected ? { ...k, ...patch } : k)))
    else onChange([...kit, { item: '', count: 1, slot: selected, ...patch }])
  }

  const slot = (id: string, label?: string) => {
    const entry = at(id)
    return (
      <button
        type="button"
        key={id}
        className={`kit-slot ${selected === id ? 'selected' : ''} ${entry?.item ? 'filled' : ''}`}
        title={entry?.item ? `${slotLabel(id)}: ${entry.item}` : slotLabel(id)}
        onClick={() => setSelected(id)}
      >
        {entry?.item ? <span className="kit-name">{shortName(entry.item)}</span> : label && <span className="kit-hint">{label}</span>}
        {entry && entry.count > 1 && <span className="kit-count">{entry.count}</span>}
      </button>
    )
  }

  return (
    <div className="settings-block">
      <div className="row between">
        <div className="settings-title">开局装备</div>
        <span className="muted small">{kit.length} 件 · 点击格子编辑</span>
      </div>
      <datalist id="kit-item-ids">
        {items.map((id) => (
          <option key={id} value={id} />
        ))}
      </datalist>
      <div className="kit-layout">
        <div className="kit-inventory">
          <div className="kit-armor">
            {ARMOR.map((a) => slot(a.slot, a.label))}
            <div className="kit-gap" />
            {slot('offhand', '副手')}
          </div>
          <div className="kit-main">
            {MAIN_ROWS.map((row, r) => (
              <div className="kit-row" key={r}>
                {row.map((id) => slot(id))}
              </div>
            ))}
            <div className="kit-gap" />
            <div className="kit-row">{HOTBAR.map((id) => slot(id, String(Number(id) + 1)))}</div>
          </div>
        </div>
        <div className="kit-edit">
          <div className="settings-title" style={{ color: 'var(--text)' }}>
            {slotLabel(selected)}
          </div>
          <Field label="物品" hint="支持组件，如 minecraft:diamond_sword[enchantments={sharpness:1}]">
            <input
              className="mono"
              list="kit-item-ids"
              value={current?.item ?? ''}
              placeholder="空"
              onChange={(e) => put({ item: e.target.value })}
            />
          </Field>
          <Field label="数量">
            <NumberInput value={current?.count ?? 1} min={1} max={64} onChange={(v) => put({ count: v })} />
          </Field>
          <button type="button" className="btn btn-ghost" disabled={!current} onClick={() => onChange(kit.filter((k) => k.slot !== selected))}>
            清空此格
          </button>
        </div>
      </div>
    </div>
  )
}
