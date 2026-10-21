import { useState, type FormEvent } from 'react'
import { api, mediaUrl } from '../api'
import { CoverPicker } from './CoverPicker'
import { Field, Modal, NumberInput, useToast } from './ui'
import type { GameMode, ModeGroup } from '../types'

export const emptyGroup = (sortOrder: number): ModeGroup => ({
  id: '',
  title: '',
  description: '',
  modeIds: [],
  enabled: true,
  sortOrder,
  coverVersion: null,
})

/** Edits a group: cover, title, description and the ordered list of its game modes. */
export function GroupEditor({
  initial,
  isNew,
  modes,
  onClose,
  onSaved,
}: {
  initial: ModeGroup
  isNew: boolean
  modes: GameMode[]
  onClose: () => void
  onSaved: () => void
}) {
  const toast = useToast()
  const [group, setGroup] = useState<ModeGroup>(initial)
  const [cover, setCover] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  const set = (patch: Partial<ModeGroup>) => setGroup((g) => ({ ...g, ...patch }))

  const toggle = (id: string) =>
    set({ modeIds: group.modeIds.includes(id) ? group.modeIds.filter((m) => m !== id) : [...group.modeIds, id] })
  const move = (index: number, delta: number) => {
    const ids = [...group.modeIds]
    const target = index + delta
    if (target < 0 || target >= ids.length) return
    ;[ids[index], ids[target]] = [ids[target], ids[index]]
    set({ modeIds: ids })
  }

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      const saved = isNew ? await api.createGroup(group) : await api.updateGroup(group)
      if (cover) await api.uploadGroupCover(saved.id, cover)
      toast.ok('已保存')
      onSaved()
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  const title = (id: string) => modes.find((m) => m.id === id)?.title ?? id

  return (
    <Modal title={isNew ? '添加分组' : `编辑分组：${initial.title}`} onClose={onClose} wide>
      <form className="form" onSubmit={save}>
        <div className="cover-row">
          <CoverPicker current={mediaUrl('group', initial.id, initial.coverVersion)} onPick={setCover} />
          <div className="form" style={{ flex: 1 }}>
            <div className="grid-2">
              <Field label="标题 Title">
                <input value={group.title} onChange={(e) => set({ title: e.target.value })} placeholder="空岛战争" required />
              </Field>
              <Field label="分组 ID">
                <input value={group.id} disabled={!isNew} onChange={(e) => set({ id: e.target.value.toLowerCase() })} placeholder="skywars" required />
              </Field>
              <Field label="排序" hint="越小越靠前">
                <NumberInput value={group.sortOrder} onChange={(v) => set({ sortOrder: v })} />
              </Field>
              <Field label="状态">
                <label className="check">
                  <input type="checkbox" checked={group.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
                  在大厅中显示
                </label>
              </Field>
            </div>
            <Field label="介绍 Desc">
              <textarea rows={3} value={group.description} onChange={(e) => set({ description: e.target.value })} />
            </Field>
          </div>
        </div>

        <div className="grid-2">
          <div className="field">
            <span className="field-label">可选模式</span>
            <div className="pick-list">
              {modes.map((m) => (
                <label key={m.id} className={`map-pick ${group.modeIds.includes(m.id) ? 'selected' : ''}`}>
                  <input type="checkbox" checked={group.modeIds.includes(m.id)} onChange={() => toggle(m.id)} />
                  <span>
                    <strong>{m.title}</strong>
                    <small className="muted">{m.id}</small>
                  </span>
                </label>
              ))}
            </div>
          </div>
          <div className="field">
            <span className="field-label">组内顺序（大厅中的显示顺序）</span>
            {group.modeIds.length === 0 && <span className="muted small">从左侧勾选模式</span>}
            <ol className="order-list">
              {group.modeIds.map((id, i) => (
                <li key={id}>
                  <span>{title(id)}</span>
                  <span className="row">
                    <button type="button" className="icon-btn" disabled={i === 0} onClick={() => move(i, -1)} aria-label="上移">
                      ↑
                    </button>
                    <button type="button" className="icon-btn" disabled={i === group.modeIds.length - 1} onClick={() => move(i, 1)} aria-label="下移">
                      ↓
                    </button>
                    <button type="button" className="icon-btn" onClick={() => toggle(id)} aria-label="移除">
                      ×
                    </button>
                  </span>
                </li>
              ))}
            </ol>
          </div>
        </div>

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
