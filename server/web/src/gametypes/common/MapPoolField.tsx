import type { GameMap } from '../../types'

/** Map pool picker for any arena type; `describe` says how well a map fits the current settings. */
export function MapPoolField({
  gameType,
  maps,
  mapIds,
  onChange,
  hint,
  describe,
}: {
  gameType: string
  maps: GameMap[]
  mapIds: string[]
  onChange: (mapIds: string[]) => void
  hint: string
  describe: (map: GameMap) => { text: string; ok: boolean }
}) {
  const typeMaps = maps.filter((m) => m.gameType?.toUpperCase() === gameType)
  const toggle = (id: string) => onChange(mapIds.includes(id) ? mapIds.filter((m) => m !== id) : [...mapIds, id])

  return (
    <div className="field">
      <span className="field-label">地图池</span>
      <span className="field-hint">{hint}</span>
      {typeMaps.length === 0 && <div className="muted">还没有该类型的地图，请先到「地图管理」导入。</div>}
      <div className="map-picks">
        {typeMaps.map((map) => {
          const fit = describe(map)
          return (
            <label key={map.id} className={`map-pick ${mapIds.includes(map.id) ? 'selected' : ''}`}>
              <input type="checkbox" checked={mapIds.includes(map.id)} onChange={() => toggle(map.id)} />
              <span>
                <strong>{map.name}</strong>
                <small className={fit.ok ? 'muted' : 'text-warn'}>{fit.text}</small>
              </span>
            </label>
          )
        })}
      </div>
    </div>
  )
}
