import type { GameMap } from '../../types'

export const BOMB = 'BOMB'

/** Mode settings of 枪战爆破 (mirrors the backend's BombDefinition; teamCount is always 2). */
export interface BombSettings {
  teamSize: number
  mapIds: string[]
  countdownSeconds: number
  endingSeconds: number
  plantSeconds: number
  defuseSeconds: number
  fuseSeconds: number
  allowBlockBreak: boolean
  allowBlockPlace: boolean
}

export function readSettings(settings: Record<string, unknown>): BombSettings {
  const num = (key: string, fallback: number) => Number(settings[key] ?? fallback)
  return {
    teamSize: num('teamSize', 5),
    mapIds: Array.isArray(settings.mapIds) ? (settings.mapIds as string[]) : [],
    countdownSeconds: num('countdownSeconds', 10),
    endingSeconds: num('endingSeconds', 8),
    plantSeconds: num('plantSeconds', 3.2),
    defuseSeconds: num('defuseSeconds', 7),
    fuseSeconds: num('fuseSeconds', 40),
    allowBlockBreak: Boolean(settings.allowBlockBreak ?? false),
    allowBlockPlace: Boolean(settings.allowBlockPlace ?? false),
  }
}

/** A bomb site (map settings of the BOMB type): block coordinates relative to the map, both corners inclusive. */
export interface BombSite {
  name: string
  x1: number
  y1: number
  z1: number
  x2: number
  y2: number
  z2: number
}

export function readSites(settings: Record<string, unknown> | undefined): BombSite[] {
  const list = settings?.sites
  if (!Array.isArray(list)) return []
  return list
    .filter((s): s is Record<string, unknown> => typeof s === 'object' && s !== null)
    .map((s) => ({
      name: String(s.name ?? ''),
      x1: Number(s.x1 ?? 0),
      y1: Number(s.y1 ?? 0),
      z1: Number(s.z1 ?? 0),
      x2: Number(s.x2 ?? 0),
      y2: Number(s.y2 ?? 0),
      z2: Number(s.z2 ?? 0),
    }))
}

const SITE_COLORS = ['#f76b15', '#e93d82', '#f5d90a', '#29c5d6', '#46a758', '#8e4ec6', '#3e8ef7', '#eeeeee']

export function siteColor(index: number) {
  return SITE_COLORS[index % SITE_COLORS.length]
}

/** Whether the box reaches outside the map (the backend warns about it, the mod clips nothing). */
export function leavesMap(site: BombSite, map: GameMap) {
  const lo = (a: number, b: number) => Math.min(a, b)
  const hi = (a: number, b: number) => Math.max(a, b)
  return (
    lo(site.x1, site.x2) < 0 ||
    lo(site.y1, site.y2) < 0 ||
    lo(site.z1, site.z2) < 0 ||
    hi(site.x1, site.x2) >= map.size.x ||
    hi(site.y1, site.y2) >= map.size.y ||
    hi(site.z1, site.z2) >= map.size.z
  )
}

export function spawnGroups(map: GameMap) {
  return map.teamSpawns.filter((t) => t.points.length > 0).length
}
