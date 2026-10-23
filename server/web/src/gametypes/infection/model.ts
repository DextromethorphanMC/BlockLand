import { api } from '../../api'
import type { GameMap } from '../../types'

export const INFECTION = 'INFECTION'

/** Mode settings of 枪战感染 (mirrors the backend's InfectionDefinition). */
export interface InfectionSettings {
  players: number
  mapIds: string[]
  countdownSeconds: number
  endingSeconds: number
  rounds: number
  roundBreakSeconds: number
  startMonsters: number
  infectionDelaySeconds: number
  motherHealthMultiplier: number
  monsterDamage: number
  monsterRespawnSeconds: number
  selectSeconds: number
  /** offered monster ids in order, first = default; empty = every monster */
  monsters: string[]
}

export function readSettings(settings: Record<string, unknown>): InfectionSettings {
  const num = (key: string, fallback: number) => Number(settings[key] ?? fallback)
  return {
    players: num('players', 8),
    mapIds: Array.isArray(settings.mapIds) ? (settings.mapIds as string[]) : [],
    countdownSeconds: num('countdownSeconds', 10),
    endingSeconds: num('endingSeconds', 8),
    rounds: num('rounds', 3),
    roundBreakSeconds: num('roundBreakSeconds', 6),
    startMonsters: num('startMonsters', 1),
    infectionDelaySeconds: num('infectionDelaySeconds', 15),
    motherHealthMultiplier: num('motherHealthMultiplier', 2),
    monsterDamage: num('monsterDamage', 6),
    monsterRespawnSeconds: num('monsterRespawnSeconds', 5),
    selectSeconds: num('selectSeconds', 10),
    monsters: Array.isArray(settings.monsters) ? (settings.monsters as string[]) : [],
  }
}

/** One monster as the game server publishes it (its infection/monsters.json). */
export interface PublishedMonster {
  id: string
  name?: string
  description?: string
  entity?: string
  health?: number
  speed?: number
  skill?: { name?: string; description?: string; cooldown?: number }
}

/** What the game server published for 枪战感染 ({} until a server connected). */
export const infectionRegistry = () => api.gameTypeRegistry(INFECTION)

export function publishedMonsters(data: Record<string, unknown> | undefined): PublishedMonster[] {
  const list = data?.monsters
  return Array.isArray(list) ? (list as PublishedMonster[]).filter((m) => m && typeof m.id === 'string' && m.id) : []
}

// ---- map settings: spawn points with roles ------------------------------------------------

export type SpawnRole = 'HUMAN' | 'MONSTER' | 'BOTH'

export interface RoleSpawn {
  x: number
  y: number
  z: number
  yaw: number
  role: SpawnRole
}

export const ROLES: { role: SpawnRole; label: string; color: string }[] = [
  { role: 'HUMAN', label: '人类', color: '#3e8ef7' },
  { role: 'MONSTER', label: '怪物', color: '#46a758' },
  { role: 'BOTH', label: '通用', color: '#f5a623' },
]

export const roleInfo = (role: SpawnRole) => ROLES.find((r) => r.role === role) ?? ROLES[2]

export function readSpawns(settings: Record<string, unknown> | undefined): RoleSpawn[] {
  const list = settings?.spawns
  return Array.isArray(list) ? (list as RoleSpawn[]) : []
}

/** Spawns the mod uses: the map's own role spawns, else team spawns (group 1 = HUMAN, group 2 = MONSTER, one group = BOTH). */
export function effectiveSpawns(map: GameMap): RoleSpawn[] {
  const own = readSpawns(map.settings)
  if (own.length) return own
  const groups = map.teamSpawns.filter((t) => t.points.length > 0).slice(0, 2)
  return groups.flatMap((g, i) => {
    const role: SpawnRole = groups.length === 1 ? 'BOTH' : i === 0 ? 'HUMAN' : 'MONSTER'
    return g.points.map((p) => ({ ...p, role }))
  })
}

export const forHumans = (s: RoleSpawn) => s.role !== 'MONSTER'
export const forMonsters = (s: RoleSpawn) => s.role !== 'HUMAN'
