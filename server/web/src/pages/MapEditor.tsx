import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api'
import { TEAM_COLORS, TopDown } from '../components/TopDown'
import { ErrorBox, Field, NumberInput, PageHeader, useLoad, useToast } from '../components/ui'
import { uiFor, useNoOverlay } from '../gametypes'
import type { GameMap, SpawnPoint, TeamSpawn } from '../types'

export function MapEditor() {
  const { id = '' } = useParams()
  const loaded = useLoad(() => api.map(id), [id])
  if (loaded.error) return <ErrorBox error={loaded.error} />
  if (!loaded.data) return <div className="muted">加载中…</div>
  // keyed so that the type specific hooks below are stable for one map
  return <MapEditorBody key={`${loaded.data.id}:${loaded.data.gameType}`} initial={loaded.data} />
}

function MapEditorBody({ initial }: { initial: GameMap }) {
  const toast = useToast()
  const preview = useLoad(() => api.mapPreview(initial.id), [initial.id])
  const types = useLoad(api.gameTypes)
  const [map, setMap] = useState<GameMap>(initial)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  const ui = uiFor(map.gameType)
  const useOverlay = ui.useMapOverlay ?? useNoOverlay
  const overlay = useOverlay(map)
  const MapPanel = ui.MapPanel

  useEffect(() => setMap(initial), [initial])

  const update = (patch: Partial<GameMap>) => {
    setMap((m) => ({ ...m, ...patch }))
    setDirty(true)
  }

  const save = async () => {
    setBusy(true)
    try {
      setMap(await api.updateMap(map))
      setDirty(false)
      toast.ok('地图配置已保存')
    } catch (e) {
      toast.error(e)
    } finally {
      setBusy(false)
    }
  }

  const replaceFile = async (file: File) => {
    const form = new FormData()
    form.append('file', file)
    setBusy(true)
    try {
      setMap(await api.replaceMapFile(map.id, form))
      setDirty(false)
      setSelected(new Set())
      preview.reload()
      toast.ok('文件已替换（同坐标的设置会保留）')
    } catch (e) {
      toast.error(e)
    } finally {
      setBusy(false)
    }
  }

  const toggleMarker = (markerId: string) => {
    const next = new Set(selected)
    if (next.has(markerId)) next.delete(markerId)
    else next.add(markerId)
    setSelected(next)
  }

  return (
    <>
      <PageHeader
        title={map.name}
        subtitle={`${map.fileName} · ${map.size.x}×${map.size.y}×${map.size.z} · 坐标均为相对地图最小角 (0,0,0)`}
        actions={
          <>
            <Link className="btn btn-ghost" to="/maps">
              ← 返回
            </Link>
            <input ref={fileInput} type="file" accept=".nbt,.schem" hidden onChange={(e) => e.target.files?.[0] && replaceFile(e.target.files[0])} />
            <button className="btn" onClick={() => fileInput.current?.click()} disabled={busy}>
              替换地图文件
            </button>
            <button className="btn btn-primary" onClick={save} disabled={busy || !dirty}>
              {dirty ? '保存修改' : '已保存'}
            </button>
          </>
        }
      />

      <div className="map-layout">
        <section className="card">
          <h2>俯视图</h2>
          <TopDown
            view={preview.data}
            map={overlay.hideTeamSpawns ? { ...map, teamSpawns: [] } : map}
            markers={overlay.markers}
            regions={overlay.regions}
            selected={selected}
            onMarkerClick={toggleMarker}
          />
          <div className="legend">
            {overlay.legend.map((l) => (
              <span key={l.label}>
                <i style={{ background: l.color }} />
                {l.label}
              </span>
            ))}
            {!overlay.hideTeamSpawns && (
              <span>
                <i className="round" style={{ background: TEAM_COLORS[0] }} />
                出生点
              </span>
            )}
          </div>
        </section>

        <section className="card">
          <h2>基本设置</h2>
          <div className="grid-2">
            <Field label="地图名称">
              <input value={map.name} onChange={(e) => update({ name: e.target.value })} />
            </Field>
            <Field label="游戏类型" hint="导入后不可更改">
              <input value={types.data?.find((t) => t.id === map.gameType)?.displayName ?? map.gameType} disabled />
            </Field>
            <Field label="虚空高度（相对 Y）" hint="玩家低于此高度视为掉入虚空">
              <NumberInput value={map.voidY ?? -10} onChange={(v) => update({ voidY: v })} />
            </Field>
            <Field label="出生点标记方块">
              <input value={map.spawnMarkerBlock ?? '（无）'} disabled />
            </Field>
          </div>
        </section>
      </div>

      <section className="card">
        <div className="row between">
          <h2>队伍出生点（{map.teamSpawns.length}）</h2>
          <button className="btn" onClick={() => update({ teamSpawns: [...map.teamSpawns, { name: `出生点 ${map.teamSpawns.length + 1}`, points: [{ x: 0, y: 0, z: 0, yaw: 0 }] }] })}>
            + 添加队伍出生点
          </button>
        </div>
        <p className="muted small">
          每组出生点对应一支队伍，队伍按顺序均匀分配（例如 8 组的 1v1 会使用第 1 和第 5 组）。同一组的多个点分给队伍成员。坐标为玩家脚下位置，可在游戏中用 <code>/bl map preview {map.id}</code> + <code>/bl map here</code> 查询。
        </p>
        <div className="spawn-grid">
          {map.teamSpawns.map((team, ti) => (
            <SpawnCard
              key={ti}
              team={team}
              color={TEAM_COLORS[ti % TEAM_COLORS.length]}
              onChange={(next) => update({ teamSpawns: map.teamSpawns.map((t, i) => (i === ti ? next : t)) })}
              onRemove={() => update({ teamSpawns: map.teamSpawns.filter((_, i) => i !== ti) })}
            />
          ))}
        </div>
      </section>

      {MapPanel && (
        <MapPanel map={map} settings={map.settings ?? {}} onChange={(settings) => update({ settings })} selected={selected} setSelected={setSelected} />
      )}
    </>
  )
}

function SpawnCard({ team, color, onChange, onRemove }: { team: TeamSpawn; color: string; onChange: (t: TeamSpawn) => void; onRemove: () => void }) {
  const setPoint = (index: number, patch: Partial<SpawnPoint>) =>
    onChange({ ...team, points: team.points.map((p, i) => (i === index ? { ...p, ...patch } : p)) })
  return (
    <div className="spawn-card" style={{ borderColor: color }}>
      <div className="row between">
        <input className="spawn-name" value={team.name} onChange={(e) => onChange({ ...team, name: e.target.value })} />
        <button className="icon-btn" onClick={onRemove} aria-label="删除出生点组">
          ×
        </button>
      </div>
      <table className="table table-compact">
        <thead>
          <tr>
            <th>X</th>
            <th>Y</th>
            <th>Z</th>
            <th>朝向</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {team.points.map((p, i) => (
            <tr key={i}>
              <td><NumberInput className="num" value={p.x} onChange={(v) => setPoint(i, { x: v })} /></td>
              <td><NumberInput className="num" value={p.y} onChange={(v) => setPoint(i, { y: v })} /></td>
              <td><NumberInput className="num" value={p.z} onChange={(v) => setPoint(i, { z: v })} /></td>
              <td><NumberInput className="num" value={p.yaw} step={15} onChange={(v) => setPoint(i, { yaw: v })} /></td>
              <td>
                <button className="icon-btn" onClick={() => onChange({ ...team, points: team.points.filter((_, j) => j !== i) })} aria-label="删除">
                  ×
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button
        className="btn btn-ghost btn-small"
        onClick={() => {
          const last = team.points[team.points.length - 1] ?? { x: 0, y: 0, z: 0, yaw: 0 }
          onChange({ ...team, points: [...team.points, { ...last, x: last.x + 1 }] })
        }}
      >
        + 出生点
      </button>
    </div>
  )
}
