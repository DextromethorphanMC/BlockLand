import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api'
import { Empty, ErrorBox, Field, Modal, NumberInput, PageHeader, useLoad, useToast } from '../components/ui'
import type { PlayerAccount } from '../types'

export function formatPlaytime(seconds: number) {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return h > 0 ? `${h} 小时 ${m} 分` : `${m} 分钟`
}

export function formatTime(iso: string | null | undefined) {
  if (!iso) return '永久'
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

type Action = { kind: 'ban' | 'mute' | 'grant'; user: PlayerAccount }

export function Users() {
  const toast = useToast()
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const users = useLoad(() => api.users(debounced), [debounced])
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
              <th>权益</th>
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
                    {!u.banned && !u.muted && <span className="muted small">正常</span>}
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
                  <div className="badges">
                    {u.entitlements.map((e) => (
                      <span key={e.key} className="badge" title={`来源: ${e.source}`}>
                        {e.key} · {e.until ? `至 ${new Date(e.until).toLocaleDateString('zh-CN')}` : '永久'}
                        <button className="badge-x" aria-label="收回" onClick={() => run(api.revoke(u.uuid, e.key), `已收回 ${e.key}`)}>
                          ×
                        </button>
                      </span>
                    ))}
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
                  <button className="btn" onClick={() => setAction({ kind: 'grant', user: u })}>
                    授予权益
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {action?.kind === 'grant' && (
        <GrantDialog user={action.user} onClose={() => setAction(null)} onDone={(p) => run(p, '已授予').then(() => setAction(null))} />
      )}
      {action && action.kind !== 'grant' && (
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

function GrantDialog({ user, onClose, onDone }: { user: PlayerAccount; onClose: () => void; onDone: (p: Promise<unknown>) => void }) {
  const products = useLoad(api.products)
  const [key, setKey] = useState('PRIORITY_QUEUE')
  const [days, setDays] = useState(30)
  const keys = [...new Set(['PRIORITY_QUEUE', ...(products.data ?? []).map((p) => p.entitlement)])]
  return (
    <Modal title={`授予权益 · ${user.name}`} onClose={onClose}>
      <form
        className="form"
        onSubmit={(e) => {
          e.preventDefault()
          onDone(api.grant(user.uuid, key, days > 0 ? days : null))
        }}
      >
        <datalist id="entitlement-keys">
          {keys.map((k) => (
            <option key={k} value={k} />
          ))}
        </datalist>
        <div className="grid-2">
          <Field label="权益标识" hint="如 PRIORITY_QUEUE（优先匹配排队）">
            <input list="entitlement-keys" value={key} onChange={(e) => setKey(e.target.value.toUpperCase())} />
          </Field>
          <Field label="天数" hint="0 = 永久；已有未过期的同名权益会顺延">
            <NumberInput value={days} min={0} onChange={setDays} />
          </Field>
        </div>
        <div className="row end">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary">授予</button>
        </div>
      </form>
    </Modal>
  )
}
