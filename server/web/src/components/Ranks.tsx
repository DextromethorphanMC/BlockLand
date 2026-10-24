import type { InventoryItem, RankTierId } from '../types'

/** 排位段位, lowest first (mirrors the mod's common/RankTier). */
export const TIERS: { id: RankTierId; label: string; color: string }[] = [
  { id: 'coal', label: '煤炭', color: '#5a5f66' },
  { id: 'iron', label: '钢铁', color: '#d8dce2' },
  { id: 'gold', label: '黄金', color: '#ffc94a' },
  { id: 'diamond', label: '钻石', color: '#5cf2e6' },
  { id: 'headhunter', label: '猎首', color: '#ff4d4d' },
]

export function tierInfo(id: string) {
  return TIERS.find((t) => t.id === id) ?? TIERS[0]
}

export function TierBadge({ tier }: { tier: string }) {
  const t = tierInfo(tier)
  return (
    <span className="badge" style={{ borderColor: t.color, color: t.color }}>
      {t.label}
    </span>
  )
}

/** "永久", "剩余 N 天 · 至 date" or "已过期" for an entitlement's end. */
export function expiryText(until: string | null | undefined) {
  if (!until) return '永久'
  const ms = Date.parse(until) - Date.now()
  if (ms <= 0) return '已过期'
  const days = Math.ceil(ms / 86_400_000)
  return `剩余 ${days} 天 · 至 ${new Date(until).toLocaleDateString('zh-CN')}`
}

/** Known inventory categories by the mod's translation key or key prefix; anything else is shown by its prefix. */
const CATEGORIES: Record<string, string> = {
  graffiti: '喷漆',
  card: '名片',
}

export function categoryLabel(item: Pick<InventoryItem, 'category' | 'key'> | { key: string; category?: string }) {
  const fromCategory = item.category?.split('.').pop()
  const prefix = item.key.includes(':') ? item.key.slice(0, item.key.indexOf(':')) : ''
  return CATEGORIES[fromCategory ?? ''] ?? CATEGORIES[prefix] ?? (prefix ? prefix : '权益')
}
