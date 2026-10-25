import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { Empty, ErrorBox, Field, Modal, NumberInput, PageHeader, useLoad, useToast } from '../components/ui'
import { TierBadge, categoryLabel, expiryText, modeTitle } from '../components/Ranks'
import type { AntiCheatCase, Entitlement, GameMode, InventoryItem, PlayerAccount, RankedLeaderEntry } from '../types'

/** A player's line in one mode's ladder. */
type ModeRank = { mode: string; entry: RankedLeaderEntry }

export function formatPlaytime(seconds: number) {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return h > 0 ? `${h} 小时 ${m} 分` : `${m} 分钟`
}

export function formatTime(iso: string | null | undefined) {
  if (!iso) return '永久'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

type Action = { kind: 'ban' | 'mute' | 'grant' | 'detail'; user: PlayerAccount }

/** An entitlement as the inventory shows it: the registry's name when the game server published the key. */
function itemName(e: Pick<Entitlement, 'key'>, registry: Map<string, InventoryItem>) {
  return registry.get(e.key.toLowerCase())?.name ?? e.key
}

export function Users() {
  const toast = useToast()
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const users = useLoad(() => api.users(debounced), [debounced])
  const registryLoad = useLoad(api.inventoryRegistry)
  const gameModes = useLoad(api.gameModes)
  // every mode has its own ladder
  const ladders = useLoad(async () => {
    const modes = await api.rankedModes()
    return Promise.all(modes.map(async (mode) => ({ mode, entries: await api.rankedLeaderboard(mode, 500) })))
  })
  const registry = new Map((registryLoad.data ?? []).map((i) => [i.key, i]))
  // players with an open anti-cheat case get a badge (one request for everyone; older backends simply show none)
  const activeCases = useLoad(() => api.antiCheatCases('ACTIVE').catch(() => [] as AntiCheatCase[]))
  const caseOf = new Map((activeCases.data ?? []).map((c) => [(c.playerUuid ?? '').toLowerCase(), c]))
  const ranks = new Map<string, ModeRank[]>()
  for (const { mode, entries } of ladders.data ?? []) {
    for (const entry of entries) ranks.set(entry.uuid, [...(ranks.get(entry.uuid) ?? []), { mode, entry }])
  }
  const [action, setAction] = useState<Action | null>(null)

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query), 250)
    return () => clearTimeout(t)
  }, [query])

  // online status and playtime change constantly
  useEffect(() => {
    const t = setInterval(users.reload, 15000)
    return () => clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const run = async (promise: Promise<unknown>, ok: string) => {
    try {
      await promise
      toast.ok(ok)
      users.reload()
    } catch (e) {
      toast.error(e)
    }
  }

  const online = users.data?.filter((u) => u.online).length ?? 0

  return (
    <>
      <PageHeader
        title="用户"
        subtitle={`进入过服务器的玩家。总在线时间由游戏服务器每 15 秒上报。当前在线 ${online} 人。`}
        actions={<input className="search" placeholder="搜索玩家名或 UUID" value={query} onChange={(e) => setQuery(e.target.value)} />}
      />
      <ErrorBox error={users.error} />
      {users.data?.length === 0 && <Empty>还没有玩家进入过服务器。</Empty>}
      {users.data && users.data.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>玩家</th>
              <th>总在线时间</th>
              <th>最后在线</th>
              <th>状态</th>
              <th>排位</th>
              <th>库存 / 权益</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {users.data.map((u) => (
              <tr key={u.uuid}>
                <td>
                  <div className="row">
                    <i className={`presence ${u.online ? 'on' : ''}`} title={u.online ? '在线' : '离线'} />
                    <strong>{u.name}</strong>
                  </div>
                  <div className="small muted">
                    <code>{u.uuid}</code>
                  </div>
                </td>
                <td>{formatPlaytime(u.totalOnlineSeconds)}</td>
                <td className="small">{u.online ? <span className="text-ok">在线</span> : formatTime(u.lastSeen)}</td>
                <td>
                  <div className="badges">
                    {u.banned && <span className="badge badge-danger" title={`${u.ban?.reason} · 至 ${formatTime(u.ban?.until)}`}>封禁中</span>}
                    {u.muted && <span className="badge badge-warn" title={`${u.mute?.reason} · 至 ${formatTime(u.mute?.until)}`}>禁言中</span>}
                    {!u.banned && !u.muted && !caseOf.has(u.uuid.toLowerCase()) && <span className="muted small">正常</span>}
                    {caseOf.has(u.uuid.toLowerCase()) && (
                      <AntiCheatBadge kase={caseOf.get(u.uuid.toLowerCase())!} />
                    )}
                    {u.preferences?.allowPartyInvites === false && (
                      <span className="badge" title="玩家在游戏设置「隐私」中关闭了「允许被邀请组队」">
                        拒绝组队邀请
                      </span>
                    )}
                  </div>
                  {(u.banned || u.muted) && (
                    <div className="small muted">
                      {u.banned ? `封禁: ${u.ban?.reason}，至 ${formatTime(u.ban?.until)}` : `禁言: ${u.mute?.reason}，至 ${formatTime(u.mute?.until)}`}
                    </div>
                  )}
                </td>
                <td>
                  <RankCell ranks={ranks.get(u.uuid)} modes={gameModes.data} />
                </td>
                <td>
                  <div className="badges">
                    {u.entitlements.slice(0, 4).map((e) => (
                      <span key={e.key} className="badge" title={`${e.key} · 来源: ${e.source}`}>
                        {itemName(e, registry)} · {e.until ? `至 ${new Date(e.until).toLocaleDateString('zh-CN')}` : '永久'}
                      </span>
                    ))}
                    {u.entitlements.length > 4 && <span className="badge">+{u.entitlements.length - 4}</span>}
                    {u.entitlements.length === 0 && <span className="muted small">无</span>}
                  </div>
                </td>
                <td className="row end">
                  {u.banned ? (
                    <button className="btn btn-ghost" onClick={() => run(api.unban(u.uuid), `已解封 ${u.name}`)}>
                      解封
                    </button>
                  ) : (
                    <button className="btn btn-danger" onClick={() => setAction({ kind: 'ban', user: u })}>
                      封禁
                    </button>
                  )}
                  {u.muted ? (
                    <button className="btn btn-ghost" onClick={() => run(api.unmute(u.uuid), `已解除 ${u.name} 的禁言`)}>
                      解除禁言
                    </button>
                  ) : (
                    <button className="btn" onClick={() => setAction({ kind: 'mute', user: u })}>
                      禁言
                    </button>
                  )}
                  <button className="btn" onClick={() => setAction({ kind: 'detail', user: u })}>
                    库存 / 排位
                  </button>
                  <button className="btn" onClick={() => setAction({ kind: 'grant', user: u })}>
                    授予物品
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {action?.kind === 'grant' && (
        <GrantDialog
          user={action.user}
          items={registryLoad.data ?? []}
          onClose={() => setAction(null)}
          onDone={(p) => run(p, '已授予').then(() => setAction(null))}
        />
      )}
      {action?.kind === 'detail' && (
        <DetailDialog
          user={users.data?.find((u) => u.uuid === action.user.uuid) ?? action.user}
          registry={registry}
          modes={gameModes.data}
          onClose={() => setAction(null)}
          onRevoke={(key) => run(api.revoke(action.user.uuid, key), `已收回 ${itemName({ key }, registry)}`)}
        />
      )}
      {action && (action.kind === 'ban' || action.kind === 'mute') && (
        <RestrictionDialog
          kind={action.kind}
          user={action.user}
          onClose={() => setAction(null)}
          onDone={(p, text) => run(p, text).then(() => setAction(null))}
        />
      )}
    </>
  )
}

const DURATIONS: { label: string; minutes: number | null }[] = [
  { label: '10 分钟', minutes: 10 },
  { label: '1 小时', minutes: 60 },
  { label: '1 天', minutes: 1440 },
  { label: '7 天', minutes: 10080 },
  { label: '30 天', minutes: 43200 },
  { label: '永久', minutes: null },
]

function RestrictionDialog({
  kind,
  user,
  onClose,
  onDone,
}: {
  kind: 'ban' | 'mute'
  user: PlayerAccount
  onClose: () => void
  onDone: (p: Promise<unknown>, text: string) => void
}) {
  const what = kind === 'ban' ? '封禁' : '禁言'
  const [reason, setReason] = useState('')
  const [minutes, setMinutes] = useState<number | null>(kind === 'ban' ? 1440 : 60)
  const submit = (e: FormEvent) => {
    e.preventDefault()
    const promise = kind === 'ban' ? api.ban(user.uuid, reason, minutes) : api.mute(user.uuid, reason, minutes)
    onDone(promise, `已${what} ${user.name}`)
  }
  return (
    <Modal title={`${what} ${user.name}`} onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <Field label="原因">
          <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="违反服务器规则" autoFocus />
        </Field>
        <div className="field">
          <span className="field-label">时长</span>
          <div className="preset-row">
            {DURATIONS.map((d) => (
              <button type="button" key={d.label} className={`chip ${minutes === d.minutes ? 'chip-active' : ''}`} onClick={() => setMinutes(d.minutes)}>
                {d.label}
              </button>
            ))}
          </div>
          {minutes !== null && (
            <div className="row">
              <NumberInput value={minutes} min={1} onChange={setMinutes} className="num" />
              <span className="muted small">分钟</span>
            </div>
          )}
        </div>
        <p className="muted small">
          {kind === 'ban' ? '在线的玩家会在 15 秒内被踢出，之后无法进入服务器。' : '禁言后玩家无法在聊天中发言。'}
        </p>
        <div className="row end">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button className={kind === 'ban' ? 'btn btn-danger' : 'btn btn-primary'}>确认{what}</button>
        </div>
      </form>
    </Modal>
  )
}

/** One line per mode whose ladder the player is on. */
function RankCell({ ranks, modes }: { ranks: ModeRank[] | undefined; modes: GameMode[] | undefined }) {
  if (!ranks?.length) return <span className="muted small">未定级</span>
  return (
    <div>
      {ranks.map(({ mode, entry }) => (
        <div key={mode} className="row">
          <span className="small muted">{modeTitle(mode, modes)}</span>
          <TierBadge tier={entry.tier} />
          <span className="small">
            {entry.score} 分 · 第 {entry.position} 名
          </span>
        </div>
      ))}
    </div>
  )
}

/** The player's inventory (owned items with their end), what they put on, and their ranked record in each mode. */
function DetailDialog({
  user,
  registry,
  modes,
  onClose,
  onRevoke,
}: {
  user: PlayerAccount
  registry: Map<string, InventoryItem>
  modes: GameMode[] | undefined
  onClose: () => void
  onRevoke: (key: string) => void
}) {
  const rank = useLoad(() => api.rankedPlayer(user.uuid), [user.uuid])
  const groups = new Map<string, Entitlement[]>()
  for (const e of user.entitlements) {
    const label = categoryLabel(registry.get(e.key.toLowerCase()) ?? { key: e.key })
    groups.set(label, [...(groups.get(label) ?? []), e])
  }
  const cosmetics = Object.entries(user.preferences?.cosmetics ?? {})
  const r = rank.data
  return (
    <Modal title={`${user.name} · 库存与排位`} onClose={onClose} wide>
      <AntiCheatCases uuid={user.uuid} />
      <div className="grid-2">
        <section>
          <h3>库存</h3>
          {user.entitlements.length === 0 && <p className="muted small">没有拥有的物品或权益（免费物品不在此列出）。</p>}
          {[...groups.entries()].map(([label, list]) => (
            <div key={label} className="field">
              <span className="field-label">{label}</span>
              <div className="inventory-list">
                {list.map((e) => (
                  <div key={e.key} className="inventory-row">
                    <div>
                      <strong>{itemName(e, registry)}</strong>
                      <div className="small muted">
                        <code>{e.key}</code> · 来源 {e.source} · {expiryText(e.until)}
                      </div>
                    </div>
                    <button className="btn btn-ghost" onClick={() => onRevoke(e.key)}>
                      收回
                    </button>
                  </div>
                ))}
              </div>
            </div>
          ))}
          <h3>已装扮</h3>
          {cosmetics.length === 0 ? (
            <p className="muted small">未装扮（使用默认名片与喷漆）。</p>
          ) : (
            <div className="badges">
              {cosmetics.map(([slot, key]) => (
                <span key={slot} className="badge" title={key}>
                  {slot} · {registry.get(key)?.name ?? key}
                </span>
              ))}
            </div>
          )}
        </section>
        <section>
          <h3>排位</h3>
          <ErrorBox error={rank.error} />
          {r?.modes.length === 0 && <p className="muted small">还没有打过排位赛。</p>}
          {r?.modes.map((m) => (
            <div key={m.modeId} className="field">
              <span className="field-label">{modeTitle(m.modeId, modes)}</span>
              <div className="row">
                <TierBadge tier={m.standing.tier} />
                <strong>{m.standing.score} 分</strong>
                <span className="muted small">{m.standing.position > 0 ? `本模式第 ${m.standing.position} 名` : '未定级'}</span>
              </div>
              <p className="small">
                {m.standing.matches} 场 · {m.standing.wins} 胜 {m.losses} 负 {m.draws} 平
              </p>
              {m.seasons.length > 0 && (
                <table className="table table-compact">
                  <thead>
                    <tr>
                      <th>赛季</th>
                      <th>最终分数</th>
                      <th>段位</th>
                      <th>排名</th>
                      <th>场次</th>
                    </tr>
                  </thead>
                  <tbody>
                    {m.seasons.map((end) => (
                      <tr key={end.season}>
                        <td>第 {end.season} 赛季</td>
                        <td>{end.score}</td>
                        <td>
                          <TierBadge tier={end.tier} />
                        </td>
                        <td>{end.position || '—'}</td>
                        <td>{end.matches}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          ))}
        </section>
      </div>
    </Modal>
  )
}

/** An open anti-cheat case, linking to it. */
function AntiCheatBadge({ kase }: { kase: AntiCheatCase }) {
  const review = kase.status === 'REVIEW'
  return (
    <Link
      to={`/anticheat?case=${encodeURIComponent(kase.id)}`}
      className={`badge ${review ? 'badge-warn' : ''}`}
      title={`可疑度 ${(kase.suspicion ?? 0).toFixed(2)} · 最高置信度 ${Math.round(kase.maxConfidence * 100)}%`}
    >
      反作弊 · {review ? '待复核' : '收集中'}
    </Link>
  )
}

const CASE_STATUS: Record<string, string> = { OPEN: '收集中', REVIEW: '待复核', BANNED: '已封禁', CLEARED: '确认误判', DISMISSED: '不予处理' }

/** The player's anti-cheat cases (loaded when the dialog opens), each linking to the 反作弊 page. */
function AntiCheatCases({ uuid }: { uuid: string }) {
  const cases = useLoad(() => api.antiCheatPlayerCases(uuid), [uuid])
  if (!cases.data || cases.data.length === 0) return null
  return (
    <section>
      <h3>反作弊案件</h3>
      <div className="inventory-list">
        {cases.data.map((c) => (
          <div key={c.id} className="inventory-row">
            <div>
              <strong>{CASE_STATUS[c.status] ?? c.status}</strong>
              <span className="small muted">
                {' '}
                · 可疑度 {(c.suspicion ?? 0).toFixed(2)} · 最高置信度 {Math.round(c.maxConfidence * 100)}% · 开案 {formatTime(c.openedAt)}
              </span>
            </div>
            <Link className="btn btn-small" to={`/anticheat?case=${encodeURIComponent(c.id)}`}>
              查看
            </Link>
          </div>
        ))}
      </div>
    </section>
  )
}

const OTHER = '__other__'

/** Grants an item from the game server's inventory registry (or any other entitlement key) for N days, 0 = permanent. */
function GrantDialog({
  user,
  items,
  onClose,
  onDone,
}: {
  user: PlayerAccount
  items: InventoryItem[]
  onClose: () => void
  onDone: (p: Promise<unknown>) => void
}) {
  const products = useLoad(api.products)
  const ownable = items.filter((i) => !i.free)
  const [pick, setPick] = useState(ownable[0]?.key ?? OTHER)
  const [custom, setCustom] = useState('PRIORITY_QUEUE')
  const [days, setDays] = useState(0)
  const otherKeys = [...new Set(['PRIORITY_QUEUE', ...(products.data ?? []).map((p) => p.entitlement)])].filter(
    (k) => !items.some((i) => i.key === k.toLowerCase()),
  )
  const byCategory = new Map<string, InventoryItem[]>()
  for (const i of ownable) byCategory.set(categoryLabel(i), [...(byCategory.get(categoryLabel(i)) ?? []), i])
  const key = pick === OTHER ? custom.trim() : pick
  return (
    <Modal title={`授予物品 · ${user.name}`} onClose={onClose}>
      <form
        className="form"
        onSubmit={(e) => {
          e.preventDefault()
          if (key) onDone(api.grant(user.uuid, key, days > 0 ? days : 0))
        }}
      >
        <datalist id="entitlement-keys">
          {otherKeys.map((k) => (
            <option key={k} value={k} />
          ))}
        </datalist>
        <Field
          label="物品"
          hint={items.length === 0 ? '游戏服务器尚未上报可拥有的物品（启动一次游戏服务器后出现）' : '免费物品所有玩家默认拥有，不在此列出'}
        >
          <select value={pick} onChange={(e) => setPick(e.target.value)}>
            {[...byCategory.entries()].map(([label, list]) => (
              <optgroup key={label} label={label}>
                {list.map((i) => (
                  <option key={i.key} value={i.key}>
                    {i.name}（{i.key}）
                  </option>
                ))}
              </optgroup>
            ))}
            <option value={OTHER}>其他权益（手动输入）</option>
          </select>
        </Field>
        <div className="grid-2">
          {pick === OTHER && (
            <Field label="权益标识" hint="如 PRIORITY_QUEUE（优先匹配排队）">
              <input list="entitlement-keys" value={custom} onChange={(e) => setCustom(e.target.value)} />
            </Field>
          )}
          <Field label="有效天数" hint="0 = 永久；已有未过期的同一物品会顺延">
            <NumberInput value={days} min={0} onChange={setDays} />
          </Field>
        </div>
        <div className="row end">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary" disabled={!key}>
            授予
          </button>
        </div>
      </form>
    </Modal>
  )
}
