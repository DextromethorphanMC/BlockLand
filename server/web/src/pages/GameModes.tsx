import { useState, type FormEvent } from 'react'
import { api, mediaUrl } from '../api'
import { CoverPicker } from '../components/CoverPicker'
import { emptyGroup, GroupEditor } from '../components/GroupEditor'
import { KitEditor } from '../components/KitEditor'
import { uiFor } from '../gametypes'
import { confirmAction, Empty, ErrorBox, Field, formatDuration, Modal, NumberInput, PageHeader, useLoad, useToast } from '../components/ui'
import type { GameMap, GameMode, GameType, ModeGroup } from '../types'

/** A mode being edited, plus the group it should belong to (groups own the membership). */
type EditedMode = GameMode & { groupId?: string }

/**
 * 小游戏管理: game modes listed under the groups they belong to (a group is a cover card in the lobby that
 * opens to its own mode list); modes in no group appear directly in the lobby.
 */
export function GameModes() {
  const toast = useToast()
  const modes = useLoad(api.gameModes)
  const groups = useLoad(api.groups)
  const types = useLoad(api.gameTypes)
  const maps = useLoad(api.maps)
  const items = useLoad(api.itemRegistry)
  const [editing, setEditing] = useState<{ mode: EditedMode; isNew: boolean } | null>(null)
  const [editingGroup, setEditingGroup] = useState<{ group: ModeGroup; isNew: boolean } | null>(null)

  const typeName = (id: string) => types.data?.find((t) => t.id === id)?.displayName ?? id
  const allGroups = [...(groups.data ?? [])].sort((a, b) => a.sortOrder - b.sortOrder)
  const grouped = new Set(allGroups.flatMap((g) => g.modeIds))
  const ungrouped = (modes.data ?? []).filter((m) => !grouped.has(m.id))
  const modeById = (id: string) => modes.data?.find((m) => m.id === id)
  const reload = () => {
    modes.reload()
    groups.reload()
  }

  const startCreate = (groupId?: string) => {
    const type = types.data?.[0]
    if (!type) return
    setEditing({
      isNew: true,
      mode: {
        id: '',
        title: '',
        type: type.id,
        description: '',
        enabled: true,
        timeLimitSeconds: null,
        settings: { ...type.defaultSettings },
        kit: [...type.defaultKit],
        groupId,
      },
    })
  }

  const toggle = async (mode: GameMode) => {
    try {
      await api.updateGameMode({ ...mode, enabled: !mode.enabled })
      modes.reload()
    } catch (e) {
      toast.error(e)
    }
  }

  const remove = async (mode: GameMode) => {
    if (!confirmAction(`确定删除模式「${mode.title}」？`)) return
    try {
      await api.deleteGameMode(mode.id)
      toast.ok('已删除')
      reload()
    } catch (e) {
      toast.error(e)
    }
  }

  const removeGroup = async (g: ModeGroup) => {
    if (!confirmAction(`确定删除分组「${g.title}」？其中的游戏模式不会被删除，会变为未分组。`)) return
    try {
      await api.deleteGroup(g.id)
      toast.ok('已删除分组')
      groups.reload()
    } catch (e) {
      toast.error(e)
    }
  }

  const card = (mode: GameMode) => (
    <article key={mode.id} className={`mode-card ${mode.enabled ? '' : 'disabled'}`}>
      {mediaUrl('mode', mode.id, mode.coverVersion) && (
        <div className="mode-cover">
          <img src={mediaUrl('mode', mode.id, mode.coverVersion)!} alt="" />
        </div>
      )}
      <div className="mode-card-head">
        <span className="tag">{typeName(mode.type)}</span>
        <label className="switch" title={mode.enabled ? '已启用' : '已停用'}>
          <input type="checkbox" checked={mode.enabled} onChange={() => toggle(mode)} />
          <span />
        </label>
      </div>
      <h3>{mode.title}</h3>
      <div className="mode-meta">
        <span className="format">{uiFor(mode.type).formatLabel(mode.settings)}</span>
        <span>存活时间：{formatDuration(mode.timeLimitSeconds)}</span>
        {mode.kit.length > 0 && <span>装备 {mode.kit.length} 件</span>}
      </div>
      {mode.description && <p className="muted small">{mode.description}</p>}
      <code className="small muted">{mode.id}</code>
      {mode.warnings && mode.warnings.length > 0 && (
        <ul className="warnings">
          {mode.warnings.map((w) => (
            <li key={w}>{w}</li>
          ))}
        </ul>
      )}
      <div className="row end">
        <button className="btn btn-ghost" onClick={() => remove(mode)}>
          删除
        </button>
        <button
          className="btn"
          onClick={() => setEditing({ mode: { ...mode, groupId: allGroups.find((g) => g.modeIds.includes(mode.id))?.id }, isNew: false })}
        >
          编辑
        </button>
      </div>
    </article>
  )

  return (
    <>
      <PageHeader
        title="小游戏管理"
        subtitle="配置出现在游戏大厅中的模式。分组在大厅中显示为带封面的卡片，点开后单独列出组内模式；未分组的模式直接显示在大厅列表中。"
        actions={
          <>
            <button className="btn" onClick={() => setEditingGroup({ isNew: true, group: emptyGroup(allGroups.length) })}>
              + 添加分组
            </button>
            <button className="btn btn-primary" onClick={() => startCreate()} disabled={!types.data}>
              + 添加游戏模式
            </button>
          </>
        }
      />
      <ErrorBox error={modes.error ?? types.error ?? groups.error} />
      {modes.data && modes.data.length === 0 && <Empty>还没有游戏模式，点击右上角添加。</Empty>}

      {allGroups.map((g) => (
        <section key={g.id} className={`group-section ${g.enabled ? '' : 'disabled'}`}>
          <header className="group-head">
            <div className="group-thumb">
              {mediaUrl('group', g.id, g.coverVersion) ? (
                <img src={mediaUrl('group', g.id, g.coverVersion)!} alt="" />
              ) : (
                <span className="muted small">无封面</span>
              )}
            </div>
            <div className="group-info">
              <div className="row">
                <h2>{g.title}</h2>
                <span className="tag">分组 · 排序 {g.sortOrder}</span>
                {!g.enabled && <span className="badge">大厅中隐藏</span>}
              </div>
              {g.description && <p className="muted small">{g.description}</p>}
              <code className="small muted">{g.id}</code>
            </div>
            <div className="row">
              <button className="btn btn-ghost" onClick={() => removeGroup(g)}>
                删除分组
              </button>
              <button className="btn" onClick={() => setEditingGroup({ group: g, isNew: false })}>
                编辑分组
              </button>
              <button className="btn" onClick={() => startCreate(g.id)} disabled={!types.data}>
                + 模式
              </button>
            </div>
          </header>
          {g.modeIds.length === 0 && <p className="text-warn small">分组内没有模式，大厅中不会显示。</p>}
          <div className="mode-grid">
            {g.modeIds
              .map(modeById)
              .filter((m): m is GameMode => !!m)
              .map(card)}
          </div>
        </section>
      ))}

      {ungrouped.length > 0 && (
        <section className="group-section">
          {allGroups.length > 0 && (
            <header className="group-head plain">
              <div className="group-info">
                <h2>未分组</h2>
                <p className="muted small">直接显示在大厅的模式列表中。</p>
              </div>
            </header>
          )}
          <div className="mode-grid">{ungrouped.map(card)}</div>
        </section>
      )}

      {editing && types.data && (
        <GameModeEditor
          initial={editing.mode}
          isNew={editing.isNew}
          types={types.data}
          groups={allGroups}
          maps={maps.data ?? []}
          items={items.data ?? []}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            reload()
          }}
        />
      )}
      {editingGroup && (
        <GroupEditor
          initial={editingGroup.group}
          isNew={editingGroup.isNew}
          modes={modes.data ?? []}
          onClose={() => setEditingGroup(null)}
          onSaved={() => {
            setEditingGroup(null)
            groups.reload()
          }}
        />
      )}
    </>
  )
}

/** Moves a mode into {@code groupId} (or out of every group when empty), keeping the order of the others. */
async function assignGroup(modeId: string, groupId: string | undefined, groups: ModeGroup[]) {
  for (const g of groups) {
    const has = g.modeIds.includes(modeId)
    if (g.id === groupId && !has) await api.updateGroup({ ...g, modeIds: [...g.modeIds, modeId] })
    if (g.id !== groupId && has) await api.updateGroup({ ...g, modeIds: g.modeIds.filter((id) => id !== modeId) })
  }
}

function GameModeEditor({
  initial,
  isNew,
  types,
  groups,
  maps,
  items,
  onClose,
  onSaved,
}: {
  initial: EditedMode
  isNew: boolean
  types: GameType[]
  groups: ModeGroup[]
  maps: GameMap[]
  items: string[]
  onClose: () => void
  onSaved: () => void
}) {
  const toast = useToast()
  const [mode, setMode] = useState<EditedMode>(initial)
  const [busy, setBusy] = useState(false)
  const set = (patch: Partial<EditedMode>) => setMode((m) => ({ ...m, ...patch }))
  const { SettingsForm } = uiFor(mode.type)
  const limited = mode.timeLimitSeconds != null
  const type = types.find((t) => t.id === mode.type)

  const changeType = (typeId: string) => {
    const type = types.find((t) => t.id === typeId)
    set({ type: typeId, settings: { ...(type?.defaultSettings ?? {}) }, kit: [...(type?.defaultKit ?? [])] })
  }

  const [cover, setCover] = useState<File | null>(null)

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      const { groupId, ...rest } = mode
      const payload = { ...rest, kit: mode.kit.filter((k) => k.item.trim()) }
      const saved = isNew ? await api.createGameMode(payload) : await api.updateGameMode(payload)
      if (cover) await api.uploadModeCover(saved.id, cover)
      if (isNew || groupId !== initial.groupId) await assignGroup(saved.id, groupId, groups)
      toast.ok('已保存')
      onSaved()
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={isNew ? '添加游戏模式' : `编辑：${initial.title}`} onClose={onClose} wide>
      <form onSubmit={save} className="form">
        <div className="grid-2">
          <Field label="标题 Title" hint="显示在游戏大厅的模式卡片上">
            <input value={mode.title} onChange={(e) => set({ title: e.target.value })} placeholder="空岛战争 4v4" required />
          </Field>
          <Field label="模式 ID" hint={isNew ? '小写字母/数字/下划线，创建后不可修改' : undefined}>
            <input
              value={mode.id}
              disabled={!isNew}
              onChange={(e) => set({ id: e.target.value.toLowerCase() })}
              placeholder="skywars_4v4"
              required
            />
          </Field>
          <Field label="游戏类型">
            <select value={mode.type} onChange={(e) => changeType(e.target.value)} disabled={!isNew}>
              {types.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.displayName}
                </option>
              ))}
            </select>
          </Field>
          <Field label="状态">
            <label className="check">
              <input type="checkbox" checked={mode.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
              在大厅中显示并允许匹配
            </label>
          </Field>
          <Field label="所属分组" hint="大厅中点开该分组后显示此模式">
            <select value={mode.groupId ?? ''} onChange={(e) => set({ groupId: e.target.value || undefined })}>
              <option value="">不分组（直接显示在大厅）</option>
              {groups.map((g) => (
                <option key={g.id} value={g.id}>
                  {g.title}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <div className="cover-row">
          <CoverPicker current={mediaUrl('mode', initial.id, initial.coverVersion)} onPick={setCover} />
          <Field label="介绍" hint="显示在大厅的模式卡片与右下角详情中">
            <textarea rows={4} value={mode.description} onChange={(e) => set({ description: e.target.value })} placeholder="例如：经典单挑，两名玩家各占一座空岛。" />
          </Field>
        </div>
        <div className="grid-2">
          <Field label="存活时间（可选）" hint="达到时间后，存活人数最多的队伍获胜；人数相同则平局">
            <label className="check">
              <input
                type="checkbox"
                checked={limited}
                onChange={(e) => set({ timeLimitSeconds: e.target.checked ? 600 : null })}
              />
              限制单局时长
            </label>
          </Field>
          {limited && (
            <Field label="时长（秒）" hint={formatDuration(mode.timeLimitSeconds)}>
              <NumberInput value={mode.timeLimitSeconds ?? 0} min={10} onChange={(v) => set({ timeLimitSeconds: v })} />
            </Field>
          )}
        </div>

        <SettingsForm settings={mode.settings} maps={maps} onChange={(settings) => set({ settings })} />

        {type?.usesKit && <KitEditor kit={mode.kit} items={items} onChange={(kit) => set({ kit })} />}

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
