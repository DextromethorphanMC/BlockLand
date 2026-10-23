import { useState } from 'react'
import { NumberInput } from '../../components/ui'
import type { GameMap } from '../../types'
import type { MapOverlay, MapPanelProps } from '../index'
import { ROLES, effectiveSpawns, forHumans, forMonsters, readSpawns, roleInfo, type RoleSpawn, type SpawnRole } from './model'

const markerId = (index: number) => `spawn:${index}`

/** 枪战感染 map data: spawn points with a role each (人类 / 怪物 / 通用). */
export function InfectionMapPanel({ map, settings, onChange, selected, setSelected }: MapPanelProps) {
  const spawns = readSpawns(settings)
  const [bulkRole, setBulkRole] = useState<SpawnRole>('BOTH')

  const set = (next: RoleSpawn[]) => onChange({ ...settings, spawns: next })
  const setSpawn = (index: number, patch: Partial<RoleSpawn>) => set(spawns.map((s, i) => (i === index ? { ...s, ...patch } : s)))
  const toggle = (index: number) => {
    const next = new Set(selected)
    if (next.has(markerId(index))) next.delete(markerId(index))
    else next.add(markerId(index))
    setSelected(next)
  }
  const add = () => {
    const last = spawns[spawns.length - 1]
    const point: RoleSpawn = last
      ? { ...last, x: last.x + 1 }
      : { x: Math.floor(map.size.x / 2), y: 1, z: Math.floor(map.size.z / 2), yaw: 0, role: 'BOTH' }
    set([...spawns, point])
  }
  const humans = spawns.filter(forHumans).length
  const monsters = spawns.filter(forMonsters).length
  const hasTeamSpawns = map.teamSpawns.some((t) => t.points.length > 0)

  return (
    <section className="card">
      <div className="row between wrap">
        <h2>枪战感染 · 角色出生点（{spawns.length}）</h2>
        <div className="row">
          <span className="muted small">已选 {selected.size} 个</span>
          <select value={bulkRole} onChange={(e) => setBulkRole(e.target.value as SpawnRole)} style={{ width: 'auto' }}>
            {ROLES.map((r) => (
              <option key={r.role} value={r.role}>
                {r.label}
              </option>
            ))}
          </select>
          <button
            className="btn"
            disabled={selected.size === 0}
            onClick={() => {
              set(spawns.map((s, i) => (selected.has(markerId(i)) ? { ...s, role: bulkRole } : s)))
              setSelected(new Set())
            }}
          >
            批量设置角色
          </button>
          {spawns.length === 0 && hasTeamSpawns && (
            <button className="btn" onClick={() => set(effectiveSpawns({ ...map, settings: {} }))}>
              从队伍出生点生成
            </button>
          )}
          <button className="btn" onClick={add}>
            + 添加出生点
          </button>
        </div>
      </div>
      <p className="muted small">
        人类 = 每回合人类的开局位置；怪物 = 被击杀的怪物复活的位置；通用 = 两者皆可。当前 {humans} 个点可供人类、{monsters} 个点可供怪物。
        列表为空时才使用上方「队伍出生点」（第 1 组 = 人类，第 2 组 = 怪物，只有 1 组时两者通用）。导入带出生点标记方块的地图时，标记自动成为通用出生点；
        替换地图文件时，同坐标的点保留原角色。点击俯视图上的方块可快速选中。
      </p>
      <div className="table-scroll">
        <table className="table table-compact">
          <thead>
            <tr>
              <th>
                <input
                  type="checkbox"
                  checked={spawns.length > 0 && selected.size === spawns.length}
                  onChange={(e) => setSelected(e.target.checked ? new Set(spawns.map((_, i) => markerId(i))) : new Set())}
                />
              </th>
              <th>#</th>
              <th>X</th>
              <th>Y</th>
              <th>Z</th>
              <th>朝向</th>
              <th>角色</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {spawns.map((s, i) => (
              <tr key={i} className={selected.has(markerId(i)) ? 'row-selected' : ''}>
                <td><input type="checkbox" checked={selected.has(markerId(i))} onChange={() => toggle(i)} /></td>
                <td className="muted">{i + 1}</td>
                <td><NumberInput className="num" value={s.x} onChange={(v) => setSpawn(i, { x: v })} /></td>
                <td><NumberInput className="num" value={s.y} onChange={(v) => setSpawn(i, { y: v })} /></td>
                <td><NumberInput className="num" value={s.z} onChange={(v) => setSpawn(i, { z: v })} /></td>
                <td><NumberInput className="num" value={s.yaw} step={15} onChange={(v) => setSpawn(i, { yaw: v })} /></td>
                <td>
                  <div className="row">
                    <i className="dot" style={{ background: roleInfo(s.role).color }} />
                    <select value={s.role} onChange={(e) => setSpawn(i, { role: e.target.value as SpawnRole })}>
                      {ROLES.map((r) => (
                        <option key={r.role} value={r.role}>
                          {r.label}
                        </option>
                      ))}
                    </select>
                  </div>
                </td>
                <td>
                  <button
                    className="icon-btn"
                    aria-label="删除出生点"
                    onClick={() => {
                      set(spawns.filter((_, j) => j !== i))
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

/** Role spawns on the top-down view; team spawns are hidden while the map has role spawns (the mod ignores them then). */
export function useInfectionOverlay(map: GameMap): MapOverlay {
  const spawns = readSpawns(map.settings)
  return {
    markers: spawns.map((s, i) => ({ id: markerId(i), x: s.x, z: s.z, color: roleInfo(s.role).color })),
    legend: ROLES.map((r) => ({ label: `${r.label}出生点`, color: r.color })),
    hideTeamSpawns: spawns.length > 0,
  }
}
