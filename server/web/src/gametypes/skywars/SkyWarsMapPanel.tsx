import { useState } from 'react'
import { Field, NumberInput, useLoad } from '../../components/ui'
import type { MapPanelProps } from '../index'
import { lootColor, readMapSettings, skyWarsApi, type SkyWarsChest } from './api'

/** 空岛战争 map data: default loot table and every chest by coordinates. */
export function SkyWarsMapPanel({ settings, onChange, selected, setSelected }: MapPanelProps) {
  const loot = useLoad(skyWarsApi.lootTables)
  const tables = loot.data ?? []
  const s = readMapSettings(settings)
  const [bulkTable, setBulkTable] = useState('')

  const set = (patch: Partial<typeof s>) => onChange({ ...settings, ...s, ...patch })
  const setChest = (index: number, patch: Partial<SkyWarsChest>) =>
    set({ chests: s.chests.map((c, i) => (i === index ? { ...c, ...patch } : c)) })
  const toggle = (index: number) => {
    const next = new Set(selected)
    const key = `chest:${index}`
    if (next.has(key)) next.delete(key)
    else next.add(key)
    setSelected(next)
  }

  return (
    <section className="card">
      <div className="row between wrap">
        <h2>空岛战争 · 箱子（{s.chests.length}）</h2>
        <div className="row">
          <span className="muted small">已选 {selected.size} 个</span>
          <select value={bulkTable} onChange={(e) => setBulkTable(e.target.value)}>
            <option value="">默认物资表</option>
            {tables.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
          <button
            className="btn"
            disabled={selected.size === 0}
            onClick={() => {
              set({ chests: s.chests.map((c, i) => (selected.has(`chest:${i}`) ? { ...c, lootTableId: bulkTable || null } : c)) })
              setSelected(new Set())
            }}
          >
            批量设置物资表
          </button>
          <button className="btn" onClick={() => set({ chests: [...s.chests, { x: 0, y: 0, z: 0, lootTableId: null, label: '' }] })}>
            + 手动添加
          </button>
        </div>
      </div>
      <div className="grid-2" style={{ maxWidth: 520, margin: '8px 0 12px' }}>
        <Field label="默认物资表" hint="未单独设置的箱子、以及地图中未登记的箱子使用此物资表">
          <select value={s.defaultLootTableId ?? ''} onChange={(e) => set({ defaultLootTableId: e.target.value || null })}>
            <option value="">（不填充）</option>
            {tables.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <p className="muted small">导入时自动识别箱子 / 陷阱箱 / 木桶。每个箱子可以按坐标单独指定物资表；点击俯视图上的方块可快速选中。</p>
      <div className="table-scroll">
        <table className="table table-compact">
          <thead>
            <tr>
              <th>
                <input
                  type="checkbox"
                  checked={s.chests.length > 0 && selected.size === s.chests.length}
                  onChange={(e) => setSelected(e.target.checked ? new Set(s.chests.map((_, i) => `chest:${i}`)) : new Set())}
                />
              </th>
              <th>备注</th>
              <th>X</th>
              <th>Y</th>
              <th>Z</th>
              <th>物资表</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {s.chests.map((c, i) => (
              <tr key={i} className={selected.has(`chest:${i}`) ? 'row-selected' : ''}>
                <td><input type="checkbox" checked={selected.has(`chest:${i}`)} onChange={() => toggle(i)} /></td>
                <td><input value={c.label} placeholder={`箱子 ${i + 1}`} onChange={(e) => setChest(i, { label: e.target.value })} /></td>
                <td><NumberInput className="num" value={c.x} onChange={(v) => setChest(i, { x: v })} /></td>
                <td><NumberInput className="num" value={c.y} onChange={(v) => setChest(i, { y: v })} /></td>
                <td><NumberInput className="num" value={c.z} onChange={(v) => setChest(i, { z: v })} /></td>
                <td>
                  <div className="row">
                    <i className="dot" style={{ background: lootColor(tables, c.lootTableId, s.defaultLootTableId) }} />
                    <select value={c.lootTableId ?? ''} onChange={(e) => setChest(i, { lootTableId: e.target.value || null })}>
                      <option value="">默认物资表</option>
                      {tables.map((t) => (
                        <option key={t.id} value={t.id}>
                          {t.name}
                        </option>
                      ))}
                    </select>
                  </div>
                </td>
                <td>
                  <button
                    className="icon-btn"
                    aria-label="删除箱子"
                    onClick={() => {
                      set({ chests: s.chests.filter((_, j) => j !== i) })
                      setSelected(new Set())
                    }}
                  >
                    ×
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
