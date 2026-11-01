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

/** One day of the check-in cycle; mirrors the backend's CheckInConfig.Reward. */
export interface CheckInReward {
  /** blank: the game shows the item's own name */
  label: string
  /** an inventory item (graffiti:skull) or a permission (PRIORITY_QUEUE); blank = nothing */
  entitlement: string
  /** 0 = permanent */
  durationDays: number
}

/** Mirrors the backend's CheckInConfig (每日签到). */
export interface CheckInConfig {
  enabled: boolean
  /** the time zone whose midnight starts a new day */
  zone: string
  days: CheckInReward[]
}

export interface CheckInPlayerLine {
  uuid: string
  name: string
  lastDay: string | null
  /** 0 once a day was missed */
  streak: number
  best: number
  total: number
  claimedToday: boolean
}

export interface CheckInStats {
  today: string
  players: number
  claimedToday: number
  recent: CheckInPlayerLine[]
}

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

/** A player's record in one mode's ladder. */
export interface RankedPlayerMode {
  modeId: string
  standing: RankedStanding
  losses: number
  draws: number
  seasons: RankedSeasonEnd[]
}

/** Every mode the player has a rating in (empty = never played ranked). */
export interface RankedPlayer {
  uuid: string
  name: string | null
  modes: RankedPlayerMode[]
}

// ---- 反作弊 (mirrors tools/anticheat/ANTICHEAT_SPEC.md §4 / §9) ----------------------------------

/** One check's settings; in presets and mode overrides every field is optional (missing = inherited). */
export interface AntiCheatCheckSettings {
  enabled?: boolean
  /** 0.1–5, multiplies the check's grace margins (> 1 = more lenient) */
  tolerance?: number
  /** VL at which the violating action is undone (0 = always / never, depending on the check) */
  setbackVl?: number
  /** VL at which the player is kicked (0 = never) */
  kickVl?: number
  /** VL lost per second without violations */
  decay?: number
  /** 0–1, share of the check in the confidence */
  weight?: number
  /** may lead to an automatic ban (otherwise only to manual review) */
  punish?: boolean
}

export type AntiCheatCheckField = keyof AntiCheatCheckSettings

export type AntiCheatCategory = 'packet' | 'movement' | 'combat' | 'world' | string

/** A check as the game server published it (checks.json). */
export interface AntiCheatCheckInfo {
  id: string
  category: AntiCheatCategory
  name: string
  description: string
  latencySensitive: boolean
  /** reported by another module through Suspicion */
  signal: boolean
  defaults: Required<AntiCheatCheckSettings>
}

export interface AntiCheatPreset {
  name: string
  description: string
  toleranceScale: number
  ping: { maxCompensationMs: number; jitterMultiplier: number; extraTicks: number; highPingMs: number }
  confidence: { alert: number; review: number; autoBan: number }
  actions: { setback: boolean; kick: boolean; autoBan: boolean }
  checks: Record<string, AntiCheatCheckSettings>
}

export interface AntiCheatConfig {
  enabled: boolean
  defaultPreset: string
  presets: Record<string, AntiCheatPreset>
  autoBan: { enabled: boolean; durationMinutes: number; reason: string }
  alerts: { staff: boolean; cooldownSeconds: number }
  evidence: { maxPerCase: number; retentionDays: number; reportCooldownSeconds: number }
  suspicion: { halfLifeDays: number; reviewScore: number }
}

/** A mode's `settings.antiCheat`. */
export interface AntiCheatModeSettings {
  enabled?: boolean
  /** missing = the game type's default preset */
  preset?: string
  /** null = the preset's */
  toleranceScale?: number | null
  checks?: Record<string, AntiCheatCheckSettings>
}

export type AntiCheatCaseStatus = 'OPEN' | 'REVIEW' | 'BANNED' | 'CLEARED' | 'DISMISSED'

export type AntiCheatAction = 'none' | 'setback' | 'cancel' | 'kick' | 'ban'

/** One piece of evidence (a report from a game server). */
export interface AntiCheatReport {
  uuid: string
  name: string
  modeId: string | null
  modeTitle: string | null
  mapId: string | null
  check: string
  category: AntiCheatCategory
  vl: number
  confidence: number
  review: boolean
  action: AntiCheatAction
  detail: string
  ping: number
  jitter: number
  world: string | null
  x: number
  y: number
  z: number
  at: string
}

export interface AntiCheatCheckStats {
  count: number
  maxVl: number
  maxConfidence: number
  lastAt: string
}

export interface AntiCheatPunishment {
  type: string
  by: string
  minutes: number | null
  reason: string
  at: string
}

export interface AntiCheatNote {
  text: string
  by: string
  at: string
}

export interface AntiCheatCase {
  id: string
  playerUuid: string
  playerName: string
  status: AntiCheatCaseStatus
  openedAt: string
  updatedAt: string
  closedAt: string | null
  suspicion: number
  maxConfidence: number
  watched: boolean
  checks: Record<string, AntiCheatCheckStats>
  modes: Record<string, number>
  /** only in the detail view */
  evidence?: AntiCheatReport[] | null
  punishments: AntiCheatPunishment[]
  notes: AntiCheatNote[]
  decision: string | null
  reviewedAt: string | null
}

/** ACTIVE = OPEN + REVIEW, CLOSED = BANNED + CLEARED + DISMISSED */
export type AntiCheatCaseFilter = 'ACTIVE' | 'OPEN' | 'REVIEW' | 'CLOSED' | 'ALL'

export type AntiCheatDecision = 'BAN' | 'CLEAR' | 'DISMISS' | 'WATCH'

export interface AntiCheatReview {
  decision: AntiCheatDecision
  /** BAN only: 0 = permanent */
  minutes?: number
  reason?: string
  note?: string
}

export interface AntiCheatSummary {
  open: number
  review: number
  bannedLast7Days: number
  reportsLast24h: number
}
