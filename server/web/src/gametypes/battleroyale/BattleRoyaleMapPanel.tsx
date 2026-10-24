import { NumberInput } from '../../components/ui'
import type { GameMap } from '../../types'
import type { MapOverlay, MapPanelProps } from '../index'
import { readSpots, SPOT_COLOR, type LootSpot } from './model'

const markerId = (index: number) => `loot:${index}`

/** 大逃杀 map data: where loot appears (team spawns are not used: everybody jumps from the plane). */
export function BattleRoyaleMapPanel({ map, settings, onChange, selected, setSelected }: MapPanelProps) {
  const spots = readSpots(settings)
  const set = (next: LootSpot[]) => onChange({ ...settings, lootSpots: next })
  const setSpot = (index: number, patch: Partial<LootSpot>) => set(spots.map((s, i) => (i === index ? { ...s, ...patch } : s)))
  const outside = (s: LootSpot) => s.x < 0 || s.y < 0 || s.z < 0 || s.x >= map.size.x || s.y >= map.size.y || s.z >= map.size.z
  const toggle = (index: number) => {
    const next = new Set(selected)
    if (next.has(markerId(index))) next.delete(markerId(index))
    else next.add(markerId(index))
    setSelected(next)
  }
  const add = () => {
    const last = spots[spots.length - 1]
    set([...spots, last ? { ...last, x: last.x + 2 } : { x: Math.floor(map.size.x / 2), y: 1, z: Math.floor(map.size.z / 2) }])
  }

  return (
    <section className="card">
      <div className="row between wrap">
        <h2>大逃杀 · 物资点（{spots.length}）</h2>
        <div className="row">
          <span className="muted small">已选 {selected.size} 个</span>
          <button
            className="btn"
            disabled={selected.size === 0}
            onClick={() => {
              set(spots.filter((_, i) => !selected.has(markerId(i))))
              setSelected(new Set())
            }}
          >
            删除所选
          </button>
          <button className="btn" onClick={add}>
            + 添加物资点
          </button>
        </div>
      </div>
      <p className="muted small">
        物资点是物品出现的方块坐标（相对地图最小角，物品落在其下方的方块上）。没有物资点时，游戏按模式的「自动物资点数量」在岛屿地表随机撒放。
        队伍出生点不使用：所有人都从飞机上跳下。点击俯视图上的方块可快速选中。
      </p>
      <div className="table-scroll" style={{ maxHeight: 360 }}>
        <table className="table table-compact">
          <thead>
            <tr>
              <th>
                <input
                  type="checkbox"
                  checked={spots.length > 0 && selected.size === spots.length}
                  onChange={(e) => setSelected(e.target.checked ? new Set(spots.map((_, i) => markerId(i))) : new Set())}
                />
              </th>
              <th>#</th>
              <th>X</th>
              <th>Y</th>
              <th>Z</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {spots.map((s, i) => (
              <tr key={i} className={selected.has(markerId(i)) ? 'row-selected' : ''}>
                <td><input type="checkbox" checked={selected.has(markerId(i))} onChange={() => toggle(i)} /></td>
                <td className={outside(s) ? 'text-warn small' : 'muted'}>{i + 1}{outside(s) && ' 超出地图'}</td>
                <td><NumberInput className="num" value={s.x} step={1} onChange={(v) => setSpot(i, { x: Math.round(v) })} /></td>
                <td><NumberInput className="num" value={s.y} step={1} onChange={(v) => setSpot(i, { y: Math.round(v) })} /></td>
                <td><NumberInput className="num" value={s.z} step={1} onChange={(v) => setSpot(i, { z: Math.round(v) })} /></td>
                <td>
                  <button
                    className="icon-btn"
                    aria-label="删除物资点"
                    onClick={() => {
                      set(spots.filter((_, j) => j !== i))
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

/** Loot spots on the top-down view; team spawns are hidden (the type does not use them). */
export function useBattleRoyaleOverlay(map: GameMap): MapOverlay {
  const spots = readSpots(map.settings)
  return {
    markers: spots.map((s, i) => ({ id: markerId(i), x: s.x, z: s.z, color: SPOT_COLOR })),
    legend: [{ label: '物资点', color: SPOT_COLOR }],
    hideTeamSpawns: true,
  }
}
