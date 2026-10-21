import { useMemo, useState, type FormEvent } from 'react'
import { api } from '../../api'
import { confirmAction, Empty, ErrorBox, Field, Modal, NumberInput, PageHeader, useLoad, useToast } from '../../components/ui'
import { skyWarsApi, type LootEntry, type LootTable } from './api'

export function LootTablesPage() {
  const toast = useToast()
  const tables = useLoad(skyWarsApi.lootTables)
  const items = useLoad(api.itemRegistry)
  const [editing, setEditing] = useState<{ table: LootTable; isNew: boolean } | null>(null)

  const remove = async (table: LootTable) => {
    if (!confirmAction(`确定删除物资表「${table.name}」？`)) return
    try {
      await skyWarsApi.deleteLootTable(table.id)
      toast.ok('已删除')
      tables.reload()
    } catch (e) {
      toast.error(e)
    }
  }

  return (
    <>
      <PageHeader
        title="空岛战争 · 物资表"
        subtitle="定义箱子中可能出现的物品与概率。在「地图管理」中可以按坐标为每个箱子指定不同的物资表。"
        actions={
          <button
            className="btn btn-primary"
            onClick={() =>
              setEditing({ isNew: true, table: { id: '', name: '', minItems: 3, maxItems: 8, entries: [{ item: 'minecraft:stone', minCount: 16, maxCount: 32, chance: 50 }] } })
            }
          >
            + 新建物资表
          </button>
        }
      />
      <ErrorBox error={tables.error} />
      {tables.data?.length === 0 && <Empty>还没有物资表。</Empty>}
      <table className="table">
        <thead>
          <tr>
            <th>名称</th>
            <th>ID</th>
            <th>物品种类</th>
            <th>每箱物品数</th>
            <th>期望物品数</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {tables.data?.map((t) => (
            <tr key={t.id}>
              <td>
                <strong>{t.name}</strong>
              </td>
              <td>
                <code>{t.id}</code>
              </td>
              <td>{t.entries.length}</td>
              <td>
                {t.minItems || 0} – {t.maxItems || '不限'}
              </td>
              <td>{(t.entries.reduce((s, e) => s + e.chance, 0) / 100).toFixed(1)}</td>
              <td className="row end">
                <button className="btn btn-ghost" onClick={() => remove(t)}>
                  删除
                </button>
                <button className="btn" onClick={() => setEditing({ table: t, isNew: false })}>
                  编辑
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {editing && (
        <LootTableEditor
          initial={editing.table}
          isNew={editing.isNew}
          items={items.data ?? []}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            tables.reload()
          }}
        />
      )}
    </>
  )
}

function LootTableEditor({
  initial,
  isNew,
  items,
  onClose,
  onSaved,
}: {
  initial: LootTable
  isNew: boolean
  items: string[]
  onClose: () => void
  onSaved: () => void
}) {
  const toast = useToast()
  const [table, setTable] = useState<LootTable>(initial)
  const [sample, setSample] = useState<{ item: string; count: number }[] | null>(null)
  const [busy, setBusy] = useState(false)
  const set = (patch: Partial<LootTable>) => setTable((t) => ({ ...t, ...patch }))
  const setEntry = (index: number, patch: Partial<LootEntry>) =>
    set({ entries: table.entries.map((e, i) => (i === index ? { ...e, ...patch } : e)) })

  const expected = useMemo(() => table.entries.reduce((s, e) => s + e.chance, 0) / 100, [table.entries])

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      if (isNew) await skyWarsApi.createLootTable(table)
      else await skyWarsApi.updateLootTable(table)
      toast.ok('已保存')
      onSaved()
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={isNew ? '新建物资表' : `编辑物资表：${initial.name}`} onClose={onClose} wide>
      <form className="form" onSubmit={save}>
        <div className="grid-4">
          <Field label="名称">
            <input value={table.name} onChange={(e) => set({ name: e.target.value })} placeholder="岛屿箱" required />
          </Field>
          <Field label="ID">
            <input value={table.id} disabled={!isNew} onChange={(e) => set({ id: e.target.value.toLowerCase() })} placeholder="island" required />
          </Field>
          <Field label="每箱最少物品" hint="不足时按概率权重补抽">
            <NumberInput value={table.minItems} min={0} max={27} onChange={(v) => set({ minItems: v })} />
          </Field>
          <Field label="每箱最多物品" hint="0 = 不限">
            <NumberInput value={table.maxItems} min={0} max={54} onChange={(v) => set({ maxItems: v })} />
          </Field>
        </div>

        <datalist id="item-ids">
          {items.map((id) => (
            <option key={id} value={id} />
          ))}
        </datalist>
        <table className="table table-compact">
          <thead>
            <tr>
              <th style={{ width: '46%' }}>物品（支持组件，如 minecraft:bow[enchantments=&#123;power:2&#125;]）</th>
              <th>最少</th>
              <th>最多</th>
              <th style={{ width: '26%' }}>出现概率</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {table.entries.map((entry, i) => (
              <tr key={i}>
                <td>
                  <input list="item-ids" className="mono" value={entry.item} onChange={(e) => setEntry(i, { item: e.target.value })} />
                </td>
                <td>
                  <NumberInput className="num" value={entry.minCount} min={1} max={99} onChange={(v) => setEntry(i, { minCount: v })} />
                </td>
                <td>
                  <NumberInput className="num" value={entry.maxCount} min={1} max={99} onChange={(v) => setEntry(i, { maxCount: v })} />
                </td>
                <td>
                  <div className="chance">
                    <input type="range" min={0} max={100} step={1} value={entry.chance} onChange={(e) => setEntry(i, { chance: Number(e.target.value) })} />
                    <NumberInput className="num" value={entry.chance} min={0} max={100} step={0.5} onChange={(v) => setEntry(i, { chance: v })} />
                    <span>%</span>
                  </div>
                </td>
                <td>
                  <button type="button" className="icon-btn" onClick={() => set({ entries: table.entries.filter((_, j) => j !== i) })} aria-label="删除">
                    ×
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="row between">
          <button
            type="button"
            className="btn"
            onClick={() => set({ entries: [...table.entries, { item: '', minCount: 1, maxCount: 1, chance: 50 }] })}
          >
            + 添加物品
          </button>
          <span className="muted small">期望每箱 {expected.toFixed(1)} 组物品（限制前）</span>
          <button type="button" className="btn" onClick={() => setSample(simulate(table))}>
            🎲 模拟开箱
          </button>
        </div>

        {sample && (
          <div className="chest-sim">
            {Array.from({ length: 27 }, (_, slot) => sample[slot]).map((s, slot) => (
              <div key={slot} className={`slot ${s ? 'filled' : ''}`} title={s?.item}>
                {s && (
                  <>
                    <span className="slot-name">{shortName(s.item)}</span>
                    {s.count > 1 && <span className="slot-count">{s.count}</span>}
                  </>
                )}
              </div>
            ))}
          </div>
        )}

        <div className="row end">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary" disabled={busy}>
            {busy ? '保存中…' : '保存'}
          </button>
        </div>
      </form>
    </Modal>
  )
}

/** Same algorithm as the mod's LootRoller, for previewing a table. */
function simulate(table: LootTable) {
  const rand = (min: number, max: number) => min + Math.floor(Math.random() * (Math.max(min, max) - min + 1))
  const roll = (e: LootEntry) => ({ item: e.item, count: rand(Math.max(1, e.minCount), e.maxCount) })
  let result = table.entries.filter((e) => Math.random() * 100 < e.chance).map(roll)
  const total = table.entries.reduce((s, e) => s + Math.max(0, e.chance), 0)
  for (let guard = 0; table.minItems > 0 && result.length < table.minItems && total > 0 && guard < 100; guard++) {
    let pick = Math.random() * total
    const entry = table.entries.find((e) => (pick -= Math.max(0, e.chance)) <= 0)
    if (entry) result.push(roll(entry))
  }
  if (table.maxItems > 0 && result.length > table.maxItems) {
    result = shuffle(result).slice(0, table.maxItems)
  }
  const slots: ({ item: string; count: number } | undefined)[] = Array(27).fill(undefined)
  const free = shuffle(Array.from({ length: 27 }, (_, i) => i))
  result.forEach((r, i) => (slots[free[i]] = r))
  return slots as { item: string; count: number }[]
}

function shuffle<T>(list: T[]) {
  const copy = [...list]
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[copy[i], copy[j]] = [copy[j], copy[i]]
  }
  return copy
}

function shortName(item: string) {
  const base = item.split('[')[0]
  return base.includes(':') ? base.split(':')[1] : base
}
