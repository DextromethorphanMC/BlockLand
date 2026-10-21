import { request } from '../../api'

export interface LootEntry {
  item: string
  minCount: number
  maxCount: number
  chance: number
}

export interface LootTable {
  id: string
  name: string
  minItems: number
  maxItems: number
  entries: LootEntry[]
  updatedAt?: string
}

/** SkyWars part of GameMap.settings (mirrors the backend's SkyWarsMapSettings). */
export interface SkyWarsChest {
  x: number
  y: number
  z: number
  lootTableId: string | null
  label: string
}

export interface SkyWarsMapSettings {
  chests: SkyWarsChest[]
  defaultLootTableId: string | null
}

export function readMapSettings(settings: Record<string, unknown> | undefined): SkyWarsMapSettings {
  const s = (settings ?? {}) as Partial<SkyWarsMapSettings>
  return { chests: Array.isArray(s.chests) ? s.chests : [], defaultLootTableId: s.defaultLootTableId ?? null }
}

const BASE = '/api/admin/skywars/loot-tables'
const json = (body: unknown) => JSON.stringify(body)

export const skyWarsApi = {
  lootTables: () => request<LootTable[]>(BASE),
  createLootTable: (table: LootTable) => request<LootTable>(BASE, { method: 'POST', body: json(table) }),
  updateLootTable: (table: LootTable) => request<LootTable>(`${BASE}/${table.id}`, { method: 'PUT', body: json(table) }),
  deleteLootTable: (id: string) => request<void>(`${BASE}/${id}`, { method: 'DELETE' }),
}

export const LOOT_COLORS = ['#f5a623', '#7ee787', '#79c0ff', '#d2a8ff', '#ff7b72', '#ffa657', '#56d4dd', '#e3b341']

export function lootColor(tables: LootTable[], tableId: string | null, defaultId: string | null) {
  const index = tables.findIndex((t) => t.id === (tableId ?? defaultId))
  return index < 0 ? '#8b8d98' : LOOT_COLORS[index % LOOT_COLORS.length]
}
