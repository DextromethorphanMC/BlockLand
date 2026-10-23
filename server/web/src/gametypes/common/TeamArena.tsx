import { Field, NumberInput } from '../../components/ui'
import type { GameMap } from '../../types'
import { MapPoolField } from './MapPoolField'

/** Settings shared by team arena games (mirrors the backend's TeamArenaSettings). */
export interface TeamArenaSettings {
  teamSize: number
  teamCount: number
  mapIds: string[]
  countdownSeconds: number
  endingSeconds: number
}

export function readTeamArena(settings: Record<string, unknown>): TeamArenaSettings {
  return {
    teamSize: Number(settings.teamSize ?? 1),
    teamCount: Number(settings.teamCount ?? 2),
    mapIds: Array.isArray(settings.mapIds) ? (settings.mapIds as string[]) : [],
    countdownSeconds: Number(settings.countdownSeconds ?? 10),
    endingSeconds: Number(settings.endingSeconds ?? 8),
  }
}

export function teamFormatLabel(raw: Record<string, unknown>) {
  const s = readTeamArena(raw)
  if (s.teamCount === 2) return `${s.teamSize}v${s.teamSize}`
  if (s.teamSize === 1) return `${s.teamCount} 人混战`
  return `${s.teamSize} 人 × ${s.teamCount} 队`
}

const PRESETS = [
  { label: '1v1', teamSize: 1, teamCount: 2 },
  { label: '2v2', teamSize: 2, teamCount: 2 },
  { label: '4v4', teamSize: 4, teamCount: 2 },
  { label: '8 人混战', teamSize: 1, teamCount: 8 },
  { label: '2 人 × 4 队', teamSize: 2, teamCount: 4 },
]

/** Team format, countdown/ending and map pool fields for any team arena type. */
export function TeamArenaFields({
  gameType,
  settings,
  onChange,
  maps,
}: {
  gameType: string
  settings: Record<string, unknown>
  onChange: (settings: Record<string, unknown>) => void
  maps: GameMap[]
}) {
  const s = readTeamArena(settings)
  const set = (patch: Partial<TeamArenaSettings>) => onChange({ ...settings, ...s, ...patch })

  return (
    <>
      <div className="preset-row">
        {PRESETS.map((p) => (
          <button
            type="button"
            key={p.label}
            className={`chip ${s.teamSize === p.teamSize && s.teamCount === p.teamCount ? 'chip-active' : ''}`}
            onClick={() => set({ teamSize: p.teamSize, teamCount: p.teamCount })}
          >
            {p.label}
          </button>
        ))}
      </div>
      <div className="grid-3">
        <Field label="每队人数">
          <NumberInput value={s.teamSize} min={1} max={64} onChange={(v) => set({ teamSize: v })} />
        </Field>
        <Field label="每局队伍数">
          <NumberInput value={s.teamCount} min={1} max={64} onChange={(v) => set({ teamCount: v })} />
        </Field>
        <Field label="每局人数">
          <input value={`${s.teamSize * s.teamCount} 人`} disabled />
        </Field>
        <Field label="开局倒计时（秒）">
          <NumberInput value={s.countdownSeconds} min={0} max={120} onChange={(v) => set({ countdownSeconds: v })} />
        </Field>
        <Field label="结算展示（秒）">
          <NumberInput value={s.endingSeconds} min={1} max={60} onChange={(v) => set({ endingSeconds: v })} />
        </Field>
      </div>

      <MapPoolField
        gameType={gameType}
        maps={maps}
        mapIds={s.mapIds}
        onChange={(mapIds) => set({ mapIds })}
        hint="不勾选任何地图 = 使用该类型的全部地图；每局随机选择一张满足队伍数的地图。"
        describe={(map) => {
          const groups = map.teamSpawns.filter((t) => t.points.length > 0).length
          const ok = groups >= s.teamCount
          return { ok, text: `${groups} 个队伍出生点${ok ? '' : ` · 少于 ${s.teamCount} 队`}` }
        }}
      />
    </>
  )
}
