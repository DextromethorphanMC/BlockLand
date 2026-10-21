import { useEffect, useMemo, useRef, useState } from 'react'
import type { MapMarker } from '../gametypes'
import type { GameMap, TopView } from '../types'

export const TEAM_COLORS = ['#e5484d', '#3e8ef7', '#46a758', '#f5d90a', '#29c5d6', '#e93d82', '#f76b15', '#eeeeee', '#2f7c57', '#0d74ce', '#8e4ec6', '#8b8d98']

/** Canvas top-down render of a map with spawn points and game type markers overlaid. */
export function TopDown({
  view,
  map,
  markers,
  selected,
  onMarkerClick,
}: {
  view: TopView | undefined
  map: GameMap
  markers: MapMarker[]
  selected: Set<string>
  onMarkerClick: (id: string) => void
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const [hover, setHover] = useState<string>('')
  const width = view?.width ?? map.size.x
  const length = view?.length ?? map.size.z
  const scale = Math.max(1, Math.floor(Math.min(560 / width, 560 / length)))
  const colors = useMemo(() => view?.palette.map(blockColor) ?? [], [view])

  useEffect(() => {
    const ctx = canvas.current?.getContext('2d')
    if (!ctx) return
    ctx.fillStyle = '#0b0f17'
    ctx.fillRect(0, 0, width * scale, length * scale)
    if (view) {
      const maxH = Math.max(1, ...view.heights)
      for (let z = 0; z < length; z++) {
        for (let x = 0; x < width; x++) {
          const cell = view.cells[x + z * width]
          if (cell < 0) continue
          ctx.fillStyle = shade(colors[cell], 0.55 + 0.45 * (view.heights[x + z * width] / maxH))
          ctx.fillRect(x * scale, z * scale, scale, scale)
        }
      }
    }
    for (const m of markers) {
      const s = Math.max(scale, 4)
      ctx.fillStyle = m.color
      ctx.fillRect(m.x * scale + scale / 2 - s / 2, m.z * scale + scale / 2 - s / 2, s, s)
      if (selected.has(m.id)) {
        ctx.strokeStyle = '#ffffff'
        ctx.lineWidth = 2
        ctx.strokeRect(m.x * scale + scale / 2 - s / 2 - 2, m.z * scale + scale / 2 - s / 2 - 2, s + 4, s + 4)
      }
    }
    map.teamSpawns.forEach((team, ti) => {
      ctx.fillStyle = TEAM_COLORS[ti % TEAM_COLORS.length]
      ctx.strokeStyle = '#000'
      team.points.forEach((p) => {
        ctx.beginPath()
        ctx.arc(p.x * scale + scale / 2, p.z * scale + scale / 2, Math.max(3, scale * 0.6), 0, Math.PI * 2)
        ctx.fill()
        ctx.stroke()
      })
      if (team.points.length) {
        const p = team.points[0]
        ctx.fillStyle = '#fff'
        ctx.font = '11px sans-serif'
        ctx.fillText(String(ti + 1), p.x * scale + scale + 4, p.z * scale)
      }
    })
  }, [view, map, markers, selected, scale, width, length, colors])

  const toBlock = (e: React.MouseEvent) => {
    const rect = e.currentTarget.getBoundingClientRect()
    return { x: Math.floor((e.clientX - rect.left) / scale), z: Math.floor((e.clientY - rect.top) / scale) }
  }

  return (
    <div className="topdown">
      <canvas
        ref={canvas}
        width={width * scale}
        height={length * scale}
        onMouseMove={(e) => {
          const { x, z } = toBlock(e)
          const cell = view?.cells[x + z * width] ?? -1
          const block = cell >= 0 ? `${view!.palette[cell]} (y=${view!.heights[x + z * width]})` : '空'
          setHover(`x=${x} z=${z} · ${block}`)
        }}
        onMouseLeave={() => setHover('')}
        onClick={(e) => {
          const { x, z } = toBlock(e)
          const marker = markers.find((m) => Math.abs(m.x - x) <= 1 && Math.abs(m.z - z) <= 1)
          if (marker) onMarkerClick(marker.id)
        }}
      />
      <div className="small muted hover-info">{hover || '将鼠标移到地图上查看坐标'}</div>
    </div>
  )
}

const KNOWN: [RegExp, string][] = [
  [/grass_block|moss|leaves|vine|fern|grass/, '#4f8a35'],
  [/water|ice/, '#3f76e4'],
  [/lava|magma/, '#d96415'],
  [/sand|sandstone/, '#d8cb8b'],
  [/dirt|mud|podzol|farmland|path/, '#7a5534'],
  [/log|wood|stem|hyphae/, '#6b5132'],
  [/plank|bookshelf|crafting|chest|barrel|fence|door|stairs|slab/, '#a2834f'],
  [/snow|white|quartz|calcite/, '#ececec'],
  [/glass/, '#a9d8f0'],
  [/gold/, '#f2c94c'],
  [/diamond/, '#5decf5'],
  [/emerald/, '#2fbf71'],
  [/iron/, '#d8d8d8'],
  [/obsidian|black/, '#1e1a2b'],
  [/netherrack|red/, '#8f2f2f'],
  [/blue/, '#2c4fa8'],
  [/stone|cobble|andesite|diorite|granite|deepslate|tuff|ore|brick/, '#7d7d7d'],
]

function blockColor(name: string) {
  for (const [re, color] of KNOWN) if (re.test(name)) return color
  let hash = 0
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) | 0
  return `hsl(${Math.abs(hash) % 360} 35% 50%)`
}

function shade(color: string, factor: number) {
  if (!color.startsWith('#')) return color
  const n = parseInt(color.slice(1), 16)
  const r = Math.round(((n >> 16) & 255) * factor)
  const g = Math.round(((n >> 8) & 255) * factor)
  const b = Math.round((n & 255) * factor)
  return `rgb(${r},${g},${b})`
}
