// Mirrors the backend records (com.blockland.backend.*).

export interface GameType {
  id: string
  displayName: string
  description: string
  defaultSettings: Record<string, unknown>
  usesKit: boolean
  defaultKit: KitItem[]
}

/** slot: '0'-'35' (0-8 hotbar) or 'head' | 'chest' | 'legs' | 'feet' | 'offhand' */
export interface KitItem {
  item: string
  count: number
  slot: string
}

export interface GameMode {
  id: string
  title: string
  type: string
  description: string
  enabled: boolean
  timeLimitSeconds: number | null
  /** 限时开放: ISO instant after which the mode closes automatically; null = permanent */
  availableUntil?: string | null
  settings: Record<string, unknown>
  kit: KitItem[]
  /** version of the cover image; null = no cover */
  coverVersion?: string | null
  updatedAt?: string
  warnings?: string[]
}

/** 模式分组: shown as one card in the lobby; opening it lists its modes. */
export interface ModeGroup {
  id: string
  title: string
  description: string
  modeIds: string[]
  enabled: boolean
  sortOrder: number
  coverVersion: string | null
  updatedAt?: string
}

export interface SpawnPoint {
  x: number
  y: number
  z: number
  yaw: number
}

export interface TeamSpawn {
  name: string
  points: SpawnPoint[]
}

export interface GameMap {
  id: string
  name: string
  gameType: string
  format: 'nbt' | 'schem'
  fileName: string
  fileHash: string
  fileSize: number
  size: { x: number; y: number; z: number }
  spawnMarkerBlock: string | null
  teamSpawns: TeamSpawn[]
  voidY: number | null
  /** owned by the map's game type */
  settings: Record<string, unknown>
  /** version of the cover shown in the lobby's map picker; null = no cover */
  coverVersion?: string | null
  updatedAt?: string
}

export interface TopView {
  width: number
  length: number
  palette: string[]
  cells: number[]
  heights: number[]
}

export interface Overview {
  contentVersion: number
  modes: number
  enabledModes: number
  maps: number
  knownItems: number
  players: number
  onlinePlayers: number
}

export interface Restriction {
  reason: string
  /** null = permanent */
  until: string | null
  createdAt: string
}

export interface Entitlement {
  key: string
  until: string | null
  source: string
  grantedAt: string
}

/** 工匠 build of one weapon: skin and, per slot, the fitted option. */
export interface WeaponBuild {
  skin: string
  slots: Record<string, string>
}

/** What the player chose in game (lobby settings, read announcements, weapon builds). */
export interface PlayerPreferences {
  allowPartyInvites: boolean
  readAnnouncements: string[]
  builds: Record<string, WeaponBuild>
  /** what the player put on: slot (card, graffiti.1 …) → owned item key */
  cosmetics?: Record<string, string>
}

export interface PlayerAccount {
  uuid: string
  name: string
  firstSeen: string
  lastSeen: string
  lastHeartbeat: string | null
  totalOnlineSeconds: number
  ban: Restriction | null
  mute: Restriction | null
  entitlements: Entitlement[]
  preferences?: PlayerPreferences
  online: boolean
  banned: boolean
  muted: boolean
}

/** 公告: shown in the game lobby; players mark them as read there. */
export interface Announcement {
  id: string
  title: string
  content: string
  pinned: boolean
  publishedAt: string
  updatedAt: string
}

export interface Product {
  id: string
  name: string
  description: string
  category: string
  priceCents: number
  currency: string
  entitlement: string
  /** 0 = permanent */
  durationDays: number
  enabled: boolean
  sortOrder: number
  imageVersion: string | null
  updatedAt?: string
}

export interface Order {
  id: string
  playerUuid: string
  playerName: string
  productId: string
  productName: string
  priceCents: number
  currency: string
  status: 'PAID' | 'FAILED'
  paymentProvider: string
  paymentReference: string
  message: string
  createdAt: string
}

/** Something players can own (库存), as the game server published it. key = the entitlement key, e.g. graffiti:smile */
export interface InventoryItem {
  key: string
  /** the mod's category translation key, e.g. blockland.inventory.category.graffiti */
  category: string
  name: string
  rarity: string
  /** every player has it without an entitlement */
  free: boolean
}

export type RankTierId = 'coal' | 'iron' | 'gold' | 'diamond' | 'headhunter'

/** Mirrors the backend's RankedConfig. */
export interface RankedConfig {
  startScore: number
  kFactor: number
  placementMatches: number
  placementK: number
  iron: number
  gold: number
  diamond: number
  headhunter: number
  headhunterTop: number
  season: number
}

export interface RankedStanding {
  score: number
  tier: RankTierId
  /** 0 = not placed yet */
  position: number
  matches: number
  wins: number
}

export interface RankedLeaderEntry {
  uuid: string
  name: string | null
  score: number
  tier: RankTierId
  position: number
  matches: number
  wins: number
}

export interface RankedSeasonEnd {
  season: number
  score: number
  tier: RankTierId
  position: number
  matches: number
  wins: number
}

export interface RankedPlayer {
  uuid: string
  name: string | null
  standing: RankedStanding
  losses: number
  draws: number
  seasons: RankedSeasonEnd[]
}
