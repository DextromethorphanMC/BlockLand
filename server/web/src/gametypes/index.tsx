import { useState, type ComponentType, type ReactElement } from 'react'
import type { GameMap } from '../types'
import { fastPvpUi } from './fastpvp'
import { infectionUi } from './infection'
import { battleRoyaleUi } from './battleroyale'
import { bombUi } from './bomb'
import { luckyPillarsUi } from './pillars'
import { aimPracticeUi, bridgePracticeUi } from './practice'
import { skyWarsUi } from './skywars'
import { uhcUi } from './uhc'
import { worldUi } from './world'

export interface SettingsFormProps {
  settings: Record<string, unknown>
  onChange: (settings: Record<string, unknown>) => void
  maps: GameMap[]
}

/** A point drawn on the map editor's top-down view (e.g. a SkyWars chest). */
export interface MapMarker {
  id: string
  x: number
  z: number
  color: string
}

/** An area drawn on the map editor's top-down view (e.g. a bomb site); block coordinates, both corners inclusive. */
export interface MapRegion {
  id: string
  x1: number
  z1: number
  x2: number
  z2: number
  color: string
  label: string
}

export interface MapOverlay {
  markers: MapMarker[]
  regions?: MapRegion[]
  legend: { label: string; color: string }[]
  /** true when the type's own points replace the map's team spawns (they are then not drawn) */
  hideTeamSpawns?: boolean
}

export interface MapPanelProps {
  map: GameMap
  settings: Record<string, unknown>
  onChange: (settings: Record<string, unknown>) => void
  /** marker ids selected on the top-down view */
  selected: Set<string>
  setSelected: (selected: Set<string>) => void
}

/**
 * Admin UI of one game type. Adding a minigame to the admin = one entry in {@link registry};
 * generic pages (modes, maps) never import type specific code.
 */
export interface GameTypeUi {
  /** Short label such as "4v4" shown on mode cards. */
  formatLabel: (settings: Record<string, unknown>) => string
  SettingsForm: ComponentType<SettingsFormProps>
  /** Editor for the type's own map data (map.settings). */
  MapPanel?: ComponentType<MapPanelProps>
  /** React hook providing top-down markers for a map of this type. */
  useMapOverlay?: (map: GameMap) => MapOverlay
  /** Hint under the mode's "存活时间" when the type gives it its own meaning (default: team with most survivors wins). */
  timeLimitHint?: string
  /** Extra admin pages owned by the type (e.g. SkyWars loot tables). */
  pages?: { path: string; label: string; element: ReactElement }[]
}

const registry: Record<string, GameTypeUi> = {
  SKYWARS: skyWarsUi,
  FASTPVP: fastPvpUi,
  INFECTION: infectionUi,
  AIM_PRACTICE: aimPracticeUi,
  BRIDGE_PRACTICE: bridgePracticeUi,
  BOMB: bombUi,
  WORLD: worldUi,
  BATTLE_ROYALE: battleRoyaleUi,
  LUCKY_PILLARS: luckyPillarsUi,
  UHC: uhcUi,
}

export function uiFor(type: string): GameTypeUi {
  return registry[type?.toUpperCase()] ?? jsonFallback
}

export function allTypePages() {
  return Object.values(registry).flatMap((ui) => ui.pages ?? [])
}

const EMPTY_OVERLAY: MapOverlay = { markers: [], legend: [] }

export function useNoOverlay(): MapOverlay {
  return EMPTY_OVERLAY
}

/** Unknown types still work: settings are edited as raw JSON and validated by the backend. */
const jsonFallback: GameTypeUi = {
  formatLabel: () => '-',
  SettingsForm: function JsonSettingsForm({ settings, onChange }: SettingsFormProps) {
    const [text, setText] = useState(JSON.stringify(settings, null, 2))
    const [invalid, setInvalid] = useState(false)
    return (
      <label className="field">
        <span className="field-label">模式设置 (JSON)</span>
        <textarea
          className={`mono ${invalid ? 'invalid' : ''}`}
          rows={10}
          value={text}
          onChange={(e) => {
            setText(e.target.value)
            try {
              onChange(JSON.parse(e.target.value))
              setInvalid(false)
            } catch {
              setInvalid(true)
            }
          }}
        />
      </label>
    )
  },
}
