import type {
  Announcement,
  GameMap,
  GameMode,
  GameType,
  InventoryItem,
  ModeGroup,
  Order,
  Overview,
  PlayerAccount,
  Product,
  RankedConfig,
  RankedLeaderEntry,
  RankedPlayer,
  TopView,
} from './types'

const TOKEN_KEY = 'blockland.adminToken'

export class ApiError extends Error {
  constructor(message: string, public status: number, public details: string[] = []) {
    super(message)
  }
}

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token: string | null) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    // storage unavailable: the session simply is not remembered
  }
  window.dispatchEvent(new Event('blockland:auth'))
}

export async function request<T>(path: string, init: RequestInit = {}, token = getToken()): Promise<T> {
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, { ...init, headers })
  if (response.status === 401) {
    if (token === getToken()) setToken(null)
    throw new ApiError('登录已失效，请重新输入管理令牌', 401)
  }
  if (response.status === 204) return undefined as T
  const text = await response.text()
  const body = text ? JSON.parse(text) : undefined
  if (!response.ok) {
    throw new ApiError(body?.message ?? `请求失败 (${response.status})`, response.status, body?.details ?? [])
  }
  return body as T
}

const json = (body: unknown) => JSON.stringify(body)

export const api = {
  login: (token: string) => request<Overview>('/api/admin/overview', {}, token),
  overview: () => request<Overview>('/api/admin/overview'),

  gameTypes: () => request<GameType[]>('/api/admin/game-types'),
  gameModes: () => request<GameMode[]>('/api/admin/game-modes'),
  createGameMode: (mode: GameMode) => request<GameMode>('/api/admin/game-modes', { method: 'POST', body: json(mode) }),
  updateGameMode: (mode: GameMode) =>
    request<GameMode>(`/api/admin/game-modes/${mode.id}`, { method: 'PUT', body: json(mode) }),
  deleteGameMode: (id: string) => request<void>(`/api/admin/game-modes/${id}`, { method: 'DELETE' }),
  uploadModeCover: (id: string, file: File) => upload<GameMode>(`/api/admin/game-modes/${id}/cover`, file),

  groups: () => request<ModeGroup[]>('/api/admin/mode-groups'),
  createGroup: (g: ModeGroup) => request<ModeGroup>('/api/admin/mode-groups', { method: 'POST', body: json(g) }),
  updateGroup: (g: ModeGroup) => request<ModeGroup>(`/api/admin/mode-groups/${g.id}`, { method: 'PUT', body: json(g) }),
  deleteGroup: (id: string) => request<void>(`/api/admin/mode-groups/${id}`, { method: 'DELETE' }),
  uploadGroupCover: (id: string, file: File) => upload<ModeGroup>(`/api/admin/mode-groups/${id}/cover`, file),

  maps: () => request<GameMap[]>('/api/admin/maps'),
  map: (id: string) => request<GameMap>(`/api/admin/maps/${id}`),
  mapPreview: (id: string) => request<TopView>(`/api/admin/maps/${id}/preview`),
  uploadMap: (form: FormData) => request<GameMap>('/api/admin/maps', { method: 'POST', body: form }),
  replaceMapFile: (id: string, form: FormData) =>
    request<GameMap>(`/api/admin/maps/${id}/file`, { method: 'POST', body: form }),
  updateMap: (map: GameMap) => request<GameMap>(`/api/admin/maps/${map.id}`, { method: 'PUT', body: json(map) }),
  deleteMap: (id: string) => request<void>(`/api/admin/maps/${id}`, { method: 'DELETE' }),
  uploadMapCover: (id: string, file: File) => upload<GameMap>(`/api/admin/maps/${id}/cover`, file),
  removeMapCover: (id: string) => request<GameMap>(`/api/admin/maps/${id}/cover`, { method: 'DELETE' }),

  /** Newest first. */
  announcements: () => request<Announcement[]>('/api/admin/announcements'),
  createAnnouncement: (a: AnnouncementInput) => request<Announcement>('/api/admin/announcements', { method: 'POST', body: json(a) }),
  updateAnnouncement: (id: string, a: AnnouncementInput) =>
    request<Announcement>(`/api/admin/announcements/${id}`, { method: 'PUT', body: json(a) }),
  deleteAnnouncement: (id: string) => request<void>(`/api/admin/announcements/${id}`, { method: 'DELETE' }),

  itemRegistry: () => request<string[]>('/api/admin/registry/items'),
  /** Everything players can own, as the game server published it (empty until a server started). */
  inventoryRegistry: () => request<InventoryItem[]>('/api/admin/registry/inventory'),
  /** What the game server published for a game type (e.g. 枪战感染 monsters); {} until then. */
  gameTypeRegistry: (type: string) => request<Record<string, unknown>>(`/api/admin/registry/game-types/${type}`),

  users: (query = '') => request<PlayerAccount[]>(`/api/admin/users${query ? `?q=${encodeURIComponent(query)}` : ''}`),
  ban: (uuid: string, reason: string, minutes: number | null) =>
    request<PlayerAccount>(`/api/admin/users/${uuid}/ban`, { method: 'POST', body: json({ reason, minutes }) }),
  unban: (uuid: string) => request<PlayerAccount>(`/api/admin/users/${uuid}/ban`, { method: 'DELETE' }),
  mute: (uuid: string, reason: string, minutes: number | null) =>
    request<PlayerAccount>(`/api/admin/users/${uuid}/mute`, { method: 'POST', body: json({ reason, minutes }) }),
  unmute: (uuid: string) => request<PlayerAccount>(`/api/admin/users/${uuid}/mute`, { method: 'DELETE' }),
  grant: (uuid: string, key: string, days: number | null) =>
    request<PlayerAccount>(`/api/admin/users/${uuid}/entitlements`, { method: 'POST', body: json({ key, days }) }),
  revoke: (uuid: string, key: string) =>
    request<PlayerAccount>(`/api/admin/users/${uuid}/entitlements/${encodeURIComponent(key)}`, { method: 'DELETE' }),

  products: () => request<Product[]>('/api/admin/shop/products'),
  createProduct: (p: Product) => request<Product>('/api/admin/shop/products', { method: 'POST', body: json(p) }),
  updateProduct: (p: Product) => request<Product>(`/api/admin/shop/products/${p.id}`, { method: 'PUT', body: json(p) }),
  deleteProduct: (id: string) => request<void>(`/api/admin/shop/products/${id}`, { method: 'DELETE' }),
  uploadProductImage: (id: string, file: File) => upload<Product>(`/api/admin/shop/products/${id}/image`, file),
  orders: () => request<Order[]>('/api/admin/shop/orders'),

  rankedConfig: () => request<RankedConfig>('/api/admin/ranked/config'),
  saveRankedConfig: (c: RankedConfig) => request<RankedConfig>('/api/admin/ranked/config', { method: 'PUT', body: json(c) }),
  resetRankedSeason: () => request<RankedConfig>('/api/admin/ranked/reset', { method: 'POST' }),
  /** The modes that have a ladder (each ranked mode has its own). */
  rankedModes: () => request<string[]>('/api/admin/ranked/modes'),
  rankedLeaderboard: (mode: string, limit = 100) =>
    request<RankedLeaderEntry[]>(`/api/admin/ranked/leaderboard?mode=${encodeURIComponent(mode)}&limit=${limit}`),
  rankedPlayer: (uuid: string) => request<RankedPlayer>(`/api/admin/ranked/players/${uuid}`),
}

export interface AnnouncementInput {
  title: string
  content: string
  pinned: boolean
}

function upload<T>(path: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  return request<T>(path, { method: 'POST', body: form })
}

/** Public URL of a stored image (no auth needed), versioned to bypass caches after a change. */
export function mediaUrl(namespace: 'shop' | 'mode' | 'group' | 'map', id: string, version: string | null | undefined) {
  return version ? `/api/public/media/${namespace}/${id}?v=${version}` : null
}

export function productImageUrl(p: { id: string; imageVersion: string | null }) {
  return mediaUrl('shop', p.id, p.imageVersion)
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) {
    return error.details.length ? `${error.message}：\n${error.details.join('\n')}` : error.message
  }
  return error instanceof Error ? error.message : String(error)
}
