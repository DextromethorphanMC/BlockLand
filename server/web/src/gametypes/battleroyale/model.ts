import type { LootRow } from '../common/LootTableField'

export type { LootRow }

export const BATTLE_ROYALE = 'BATTLE_ROYALE'

/** Mode settings of 大逃杀 (mirrors the backend's BattleRoyaleDefinition). */
export interface BattleRoyaleSettings {
  players: number
  mapIds: string[]
  countdownSeconds: number
  endingSeconds: number
  introSeconds: number
  planeHeight: number
  planeSpeed: number
  autoDeployHeight: number
  zoneEnabled: boolean
  zoneDelaySeconds: number
  zoneShrinkSeconds: number
  zoneFinalRadius: number
  zoneDamage: number
  lootPerSpot: { min: number; max: number }
  autoLootSpots: number
  loot: LootRow[]
  allowBlockBreak: boolean
  allowBlockPlace: boolean
}

export function readSettings(settings: Record<string, unknown>): BattleRoyaleSettings {
  const num = (key: string, fallback: number) => Number(settings[key] ?? fallback)
  const perSpot = (settings.lootPerSpot ?? {}) as Record<string, unknown>
  const loot = Array.isArray(settings.loot) ? (settings.loot as Record<string, unknown>[]) : []
  return {
    players: num('players', 16),
    mapIds: Array.isArray(settings.mapIds) ? (settings.mapIds as string[]) : [],
    countdownSeconds: num('countdownSeconds', 5),
    endingSeconds: num('endingSeconds', 8),
    introSeconds: num('introSeconds', 6),
    planeHeight: num('planeHeight', 45),
    planeSpeed: num('planeSpeed', 14),
    autoDeployHeight: num('autoDeployHeight', 18),
    zoneEnabled: Boolean(settings.zoneEnabled ?? true),
    zoneDelaySeconds: num('zoneDelaySeconds', 60),
    zoneShrinkSeconds: num('zoneShrinkSeconds', 240),
    zoneFinalRadius: num('zoneFinalRadius', 8),
    zoneDamage: num('zoneDamage', 1),
    lootPerSpot: { min: Number(perSpot.min ?? 1), max: Number(perSpot.max ?? 3) },
    autoLootSpots: num('autoLootSpots', 80),
    loot: loot.map((r) => ({
      item: String(r.item ?? ''),
      min: Number(r.min ?? 1),
      max: Number(r.max ?? 1),
      weight: Number(r.weight ?? 10),
    })),
    allowBlockBreak: Boolean(settings.allowBlockBreak ?? false),
    allowBlockPlace: Boolean(settings.allowBlockPlace ?? false),
  }
}

/** A loot spot (map settings of the type): block coordinates relative to the map's minimum corner. */
export interface LootSpot {
  x: number
  y: number
  z: number
}

export function readSpots(settings: Record<string, unknown> | undefined): LootSpot[] {
  const list = settings?.lootSpots
  if (!Array.isArray(list)) return []
  return list
    .filter((s): s is Record<string, unknown> => typeof s === 'object' && s !== null)
    .map((s) => ({ x: Number(s.x ?? 0), y: Number(s.y ?? 0), z: Number(s.z ?? 0) }))
}

export const SPOT_COLOR = '#f5d90a'
