import { NumberInput } from '../../components/ui'
import type { GameMap } from '../../types'
import type { MapOverlay, MapPanelProps } from '../index'
import { leavesMap, readSites, siteColor, spawnGroups, type BombSite } from './model'

const regionId = (index: number) => `site:${index}`
const LETTERS = 'ABCDEFGH'

/** 枪战爆破 map data: the bomb sites, boxes the attackers must stand in to plant. */
export function BombMapPanel({ map, settings, onChange, selected, setSelected }: MapPanelProps) {
  const sites = readSites(settings)
  const set = (next: BombSite[]) => onChange({ ...settings, sites: next })
  const setSite = (index: number, patch: Partial<BombSite>) => set(sites.map((s, i) => (i === index ? { ...s, ...patch } : s)))
  const used = new Set(sites.map((s) => s.name.trim().toUpperCase()))
  const free = [...LETTERS].filter((l) => !used.has(l))
  const groups = spawnGroups(map)

  /** A 5 × 3 × 5 box at the map centre, standing on the spawns' floor level. */
  const add = (name: string) => {
    const floor = Math.floor(map.teamSpawns.find((t) => t.points.length > 0)?.points[0].y ?? 1)
    const x1 = Math.max(0, Math.floor(map.size.x / 2) - 2)
    const z1 = Math.max(0, Math.floor(map.size.z / 2) - 2)
    set([...sites, { name, x1, y1: floor, z1, x2: x1 + 4, y2: floor + 2, z2: z1 + 4 }])
  }
  const toggle = (index: number) => {
    const next = new Set(selected)
    if (next.has(regionId(index))) next.delete(regionId(index))
    else next.add(regionId(index))
    setSelected(next)
  }

  return (
    <section className="card">
      <div className="row between wrap">
        <h2>枪战爆破 · 包点（{sites.length}）</h2>
        <div className="row">
          {free.slice(0, 2).map((letter) => (
            <button key={letter} className="btn" disabled={sites.length >= LETTERS.length} onClick={() => add(letter)}>
              + 添加包点 {letter}
            </button>
          ))}
        </div>
      </div>
      <p className="muted small">
        包点是一个长方体区域（相对地图最小角的方块坐标，两个角都包含在内）：进攻方站在区域内手持炸药包按住左键即可安放。
        上方「队伍出生点」第 1 组为进攻方、第 2 组为防守方{groups < 2 && <span className="text-warn">（当前只有 {groups} 组，至少需要 2 组）</span>}。
        名称 1-8 个字符且不能重复，最多 8 个包点；替换地图文件时包点保留。点击俯视图上的区域可选中对应行。
      </p>
      {sites.length === 0 && <div className="text-warn small">还没有包点，该地图无法用于枪战爆破。</div>}
      <div className="table-scroll">
        <table className="table table-compact">
          <thead>
            <tr>
              <th>名称</th>
              <th>X1</th>
              <th>Y1</th>
              <th>Z1</th>
              <th>X2</th>
              <th>Y2</th>
              <th>Z2</th>
              <th>大小</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {sites.map((s, i) => (
              <tr key={i} className={selected.has(regionId(i)) ? 'row-selected' : ''} onClick={(e) => e.target === e.currentTarget && toggle(i)}>
                <td>
                  <div className="row">
                    <i className="dot" style={{ background: siteColor(i) }} />
                    <input className="num" value={s.name} maxLength={8} onChange={(e) => setSite(i, { name: e.target.value })} />
                  </div>
                </td>
                <td><NumberInput className="num" value={s.x1} step={1} onChange={(v) => setSite(i, { x1: Math.round(v) })} /></td>
                <td><NumberInput className="num" value={s.y1} step={1} onChange={(v) => setSite(i, { y1: Math.round(v) })} /></td>
                <td><NumberInput className="num" value={s.z1} step={1} onChange={(v) => setSite(i, { z1: Math.round(v) })} /></td>
                <td><NumberInput className="num" value={s.x2} step={1} onChange={(v) => setSite(i, { x2: Math.round(v) })} /></td>
                <td><NumberInput className="num" value={s.y2} step={1} onChange={(v) => setSite(i, { y2: Math.round(v) })} /></td>
                <td><NumberInput className="num" value={s.z2} step={1} onChange={(v) => setSite(i, { z2: Math.round(v) })} /></td>
                <td className={leavesMap(s, map) ? 'text-warn small' : 'muted small'}>
                  {Math.abs(s.x2 - s.x1) + 1}×{Math.abs(s.y2 - s.y1) + 1}×{Math.abs(s.z2 - s.z1) + 1}
                  {leavesMap(s, map) && ' 超出地图'}
                </td>
                <td>
                  <button
                    className="icon-btn"
                    aria-label="删除包点"
                    onClick={() => {
                      set(sites.filter((_, j) => j !== i))
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

/** The sites as labelled areas on the top-down view; team spawns stay visible (they are the two sides). */
export function useBombOverlay(map: GameMap): MapOverlay {
  const sites = readSites(map.settings)
  return {
    markers: [],
    regions: sites.map((s, i) => ({ id: regionId(i), x1: s.x1, z1: s.z1, x2: s.x2, z2: s.z2, color: siteColor(i), label: s.name })),
    legend: sites.length ? [{ label: '包点', color: siteColor(0) }] : [],
  }
}
