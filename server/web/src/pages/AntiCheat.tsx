import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api'
import {
  CheckOverridesTable,
  categoryName,
  checkName,
  presetLabel,
  resolveCheck,
} from '../components/AntiCheatChecks'
import { modeTitle } from '../components/Ranks'
import { confirmAction, Empty, ErrorBox, Field, Modal, NumberInput, PageHeader, useLoad, useToast } from '../components/ui'
import type {
  AntiCheatAction,
  AntiCheatCase,
  AntiCheatCaseFilter,
  AntiCheatCaseStatus,
  AntiCheatCheckInfo,
  AntiCheatConfig,
  AntiCheatDecision,
  AntiCheatPreset,
  GameMode,
} from '../types'
import { formatTime } from './Users'

/** Case states as the admin sees them. */
const STATUS: Record<AntiCheatCaseStatus, { label: string; badge: string }> = {
  OPEN: { label: '收集中', badge: 'badge' },
  REVIEW: { label: '待复核', badge: 'badge badge-warn' },
  BANNED: { label: '已封禁', badge: 'badge badge-danger' },
  CLEARED: { label: '确认误判', badge: 'badge badge-ok' },
  DISMISSED: { label: '不予处理', badge: 'badge badge-ended' },
}

const FILTERS: { id: AntiCheatCaseFilter; label: string }[] = [
  { id: 'ACTIVE', label: '进行中' },
  { id: 'REVIEW', label: '待复核' },
  { id: 'OPEN', label: '收集中' },
  { id: 'CLOSED', label: '已关闭' },
  { id: 'ALL', label: '全部' },
]

const ACTIONS: Record<AntiCheatAction, { label: string; className: string }> = {
  none: { label: '记录', className: 'muted' },
  setback: { label: '回退', className: 'text-warn' },
  cancel: { label: '取消', className: 'text-warn' },
  kick: { label: '踢出', className: 'text-danger' },
  ban: { label: '封禁', className: 'text-danger' },
}

const percent = (v: number | null | undefined) => `${Math.round((v ?? 0) * 100)}%`
/** A number with fixed decimals; '—' when the server left it out. */
const fixed = (v: number | null | undefined, digits: number) => (v == null || !Number.isFinite(v) ? '—' : v.toFixed(digits))

function StatusBadge({ c }: { c: Pick<AntiCheatCase, 'status' | 'watched'> }) {
  const s = STATUS[c.status] ?? { label: c.status, badge: 'badge' }
  return (
    <span className="badges">
      <span className={s.badge}>{s.label}</span>
      {c.watched && <span className="badge badge-limited">继续观察</span>}
    </span>
  )
}

/** The checks a case was reported for, most often first. */
function topChecks(c: AntiCheatCase) {
  return Object.entries(c.checks ?? {}).sort((a, b) => b[1].count - a[1].count)
}

/**
 * 反作弊: the cases the game servers' reports opened (review, ban, clear) and the global settings with the presets each
 * mode picks from. Contract: tools/anticheat/ANTICHEAT_SPEC.md §9–§10.
 */
export function AntiCheat() {
  const [params, setParams] = useSearchParams()
  const tab = params.get('tab') === 'settings' ? 'settings' : 'cases'
  const caseId = params.get('case')
  const registry = useLoad(api.antiCheatRegistry)
  const summary = useLoad(api.antiCheatSummary)
  const go = (next: Record<string, string>) => setParams(next)
  const s = summary.data

  return (
    <>
      <PageHeader
        title="反作弊"
        subtitle={
          s
            ? `待复核 ${s.review} · 收集中 ${s.open} · 近 7 天封禁 ${s.bannedLast7Days} · 近 24 小时上报 ${s.reportsLast24h} 条。游戏服务器检测移动、数据包与战斗，按模式的预设回退、踢出并上报证据；自动封禁需要在设置中开启。`
            : '游戏服务器检测移动、数据包与战斗，按模式的预设回退、踢出并上报证据；可疑玩家在这里复核。'
        }
        actions={
          <div className="preset-row" style={{ margin: 0 }}>
            <button className={`chip ${tab === 'cases' ? 'chip-active' : ''}`} onClick={() => go({})}>
              案件
            </button>
            <button className={`chip ${tab === 'settings' ? 'chip-active' : ''}`} onClick={() => go({ tab: 'settings' })}>
              设置
            </button>
          </div>
        }
      />
      {tab === 'cases' &&
        (caseId ? (
          <CaseDetail id={caseId} registry={registry.data} onBack={() => go({})} onChanged={summary.reload} />
        ) : (
          <CaseList registry={registry.data} onOpen={(id) => go({ case: id })} />
        ))}
      {tab === 'settings' && <SettingsView registry={registry.data} />}
    </>
  )
}

// ---- case list ------------------------------------------------------------------------------------

function CaseList({ registry, onOpen }: { registry: AntiCheatCheckInfo[] | undefined; onOpen: (id: string) => void }) {
  const [filter, setFilter] = useState<AntiCheatCaseFilter>('ACTIVE')
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const cases = useLoad(() => api.antiCheatCases(filter, debounced), [filter, debounced])

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query.trim()), 250)
    return () => clearTimeout(t)
  }, [query])

  // new reports keep arriving while players are online
  useEffect(() => {
    const t = setInterval(cases.reload, 15000)
    return () => clearInterval(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <section>
      <div className="row between" style={{ marginBottom: 14 }}>
        <div className="preset-row" style={{ margin: 0 }}>
          {FILTERS.map((f) => (
            <button key={f.id} className={`chip ${filter === f.id ? 'chip-active' : ''}`} onClick={() => setFilter(f.id)}>
              {f.label}
            </button>
          ))}
        </div>
        <input className="search" placeholder="搜索玩家名或 UUID" value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      <ErrorBox error={cases.error} />
      {cases.data?.length === 0 && (
        <Empty>{filter === 'ACTIVE' ? '没有进行中的案件：游戏服务器上报可疑行为后会在这里开案。' : '没有符合条件的案件。'}</Empty>
      )}
      {cases.data && cases.data.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>玩家</th>
              <th>状态</th>
              <th title="每条上报累加置信度，按半衰期衰减；达到复核分数进入待复核">可疑度</th>
              <th>最高置信度</th>
              <th>主要检测</th>
              <th>最后更新</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {cases.data.map((c) => (
              <tr key={c.id} className={c.status === 'REVIEW' ? 'ac-review' : ''}>
                <td>
                  <strong>{c.playerName}</strong>
                  <div className="small muted">
                    <code>{c.playerUuid}</code>
                  </div>
                </td>
                <td>
                  <StatusBadge c={c} />
                </td>
                <td>
                  <strong>{fixed(c.suspicion, 2)}</strong>
                </td>
                <td>
                  <ConfidenceBar value={c.maxConfidence} />
                </td>
                <td>
                  <div className="badges">
                    {topChecks(c)
                      .slice(0, 3)
                      .map(([id, stats]) => (
                        <span key={id} className="badge" title={id}>
                          {checkName(id, registry)} × {stats.count}
                        </span>
                      ))}
                    {topChecks(c).length > 3 && <span className="badge">+{topChecks(c).length - 3}</span>}
                  </div>
                </td>
                <td className="small">{formatTime(c.updatedAt)}</td>
                <td className="row end">
                  <button className="btn" onClick={() => onOpen(c.id)}>
                    查看
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

function ConfidenceBar({ value }: { value: number }) {
  const v = Math.max(0, Math.min(1, value ?? 0))
  const color = v >= 0.9 ? 'var(--danger)' : v >= 0.7 ? 'var(--accent)' : v >= 0.5 ? 'var(--warn)' : 'var(--muted)'
  return (
    <div className="ac-bar" title={percent(v)}>
      <span style={{ width: `${v * 100}%`, background: color }} />
      <em>{percent(v)}</em>
    </div>
  )
}

// ---- case detail ----------------------------------------------------------------------------------

function CaseDetail({
  id,
  registry,
  onBack,
  onChanged,
}: {
  id: string
  registry: AntiCheatCheckInfo[] | undefined
  onBack: () => void
  onChanged: () => void
}) {
  const toast = useToast()
  const kase = useLoad(() => api.antiCheatCase(id), [id])
  const modes = useLoad(api.gameModes)
  const config = useLoad(api.antiCheatConfig)
  const [deciding, setDeciding] = useState<AntiCheatDecision | null>(null)
  const [note, setNote] = useState('')
  const [checkFilter, setCheckFilter] = useState('')
  const c = kase.data

  const after = (next: AntiCheatCase | void, text: string) => {
    toast.ok(text)
    if (next) kase.setData(next)
    else kase.reload()
    onChanged()
  }

  const addNote = async (e: FormEvent) => {
    e.preventDefault()
    if (!note.trim()) return
    try {
      after(await api.addAntiCheatNote(id, note.trim()), '已添加备注')
      setNote('')
    } catch (err) {
      toast.error(err)
    }
  }

  const remove = async () => {
    if (!c || !confirmAction(`确定删除 ${c.playerName} 的这个案件？证据与备注会一起删除，无法恢复（已执行的封禁不会撤销）。`)) return
    try {
      await api.deleteAntiCheatCase(id)
      toast.ok('已删除案件')
      onChanged()
      onBack()
    } catch (err) {
      toast.error(err)
    }
  }

  if (!c) {
    return (
      <>
        <button className="link-btn" onClick={onBack}>
          ‹ 返回案件列表
        </button>
        <ErrorBox error={kase.error} />
      </>
    )
  }

  const active = c.status === 'OPEN' || c.status === 'REVIEW'
  const evidence = [...(c.evidence ?? [])].sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
  const shown = checkFilter ? evidence.filter((e) => e.check === checkFilter) : evidence
  const reviewScore = config.data?.suspicion.reviewScore

  return (
    <>
      <button className="link-btn" onClick={onBack} style={{ marginBottom: 12 }}>
        ‹ 返回案件列表
      </button>
      <section className="card">
        <div className="row between wrap">
          <div>
            <div className="row">
              <h2 style={{ margin: 0 }}>{c.playerName}</h2>
              <StatusBadge c={c} />
            </div>
            <div className="small muted">
              <code>{c.playerUuid}</code> · 案件 <code>{c.id}</code>
            </div>
          </div>
          <div className="row wrap">
            {active && (
              <>
                <button className="btn btn-danger" onClick={() => setDeciding('BAN')}>
                  封禁
                </button>
                <button className="btn" onClick={() => setDeciding('CLEAR')}>
                  确认误判
                </button>
                <button className="btn" onClick={() => setDeciding('DISMISS')}>
                  不予处理
                </button>
                <button className="btn" onClick={() => setDeciding('WATCH')} disabled={c.watched && c.status === 'OPEN'}>
                  继续观察
                </button>
              </>
            )}
            <button className="btn btn-ghost" onClick={remove}>
              删除
            </button>
          </div>
        </div>
        <div className="stat-grid ac-stats">
          <div className="stat">
            <span className="stat-value">
              {fixed(c.suspicion, 2)}
              {reviewScore != null && <small>/{reviewScore}</small>}
            </span>
            <span className="stat-label">可疑度（达到右侧分数进入待复核）</span>
          </div>
          <div className="stat">
            <span className="stat-value">{percent(c.maxConfidence)}</span>
            <span className="stat-label">最高置信度</span>
          </div>
          <div className="stat">
            <span className="stat-value">{evidence.length}</span>
            <span className="stat-label">保留的证据</span>
          </div>
          <div className="stat">
            <span className="stat-label">开案 {formatTime(c.openedAt)}</span>
            <span className="stat-label">更新 {formatTime(c.updatedAt)}</span>
            {c.closedAt && <span className="stat-label">关闭 {formatTime(c.closedAt)}</span>}
            {c.reviewedAt && (
              <span className="stat-label">
                复核 {formatTime(c.reviewedAt)}
                {c.decision ? ` · ${decisionLabel(c.decision)}` : ''}
              </span>
            )}
          </div>
        </div>
      </section>

      <div className="grid-2">
        <section className="card">
          <h2>检测统计</h2>
          {topChecks(c).length === 0 ? (
            <p className="muted small">没有记录。</p>
          ) : (
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>检测</th>
                  <th>次数</th>
                  <th>最高 VL</th>
                  <th>最高置信度</th>
                  <th>最后</th>
                </tr>
              </thead>
              <tbody>
                {topChecks(c).map(([checkId, stats]) => {
                  const info = registry?.find((r) => r.id === checkId)
                  return (
                    <tr key={checkId} className={checkFilter === checkId ? 'row-selected' : ''}>
                      <td title={info?.description}>
                        <button className="link-btn" onClick={() => setCheckFilter(checkFilter === checkId ? '' : checkId)}>
                          {info?.name ?? checkId}
                        </button>
                        <div className="small muted">
                          {info ? categoryName(info.category) + ' · ' : ''}
                          <code>{checkId}</code>
                        </div>
                      </td>
                      <td>{stats.count}</td>
                      <td>{fixed(stats.maxVl, 1)}</td>
                      <td>{percent(stats.maxConfidence)}</td>
                      <td className="small">{formatTime(stats.lastAt)}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          )}
          {Object.keys(c.modes ?? {}).length > 0 && (
            <>
              <h3>出现在</h3>
              <div className="badges">
                {Object.entries(c.modes)
                  .sort((a, b) => b[1] - a[1])
                  .map(([modeId, count]) => (
                    <span key={modeId} className="badge" title={modeId}>
                      {modeTitle(modeId, modes.data)} × {count}
                    </span>
                  ))}
              </div>
            </>
          )}
        </section>

        <section className="card">
          <h2>处罚与备注</h2>
          {(c.punishments ?? []).length === 0 && <p className="muted small">没有处罚记录。</p>}
          {(c.punishments ?? []).length > 0 && (
            <div className="inventory-list">
              {c.punishments.map((p, i) => (
                <div key={i} className="inventory-row">
                  <div>
                    <strong className={p.type === 'BAN' ? 'text-danger' : ''}>
                      {p.type === 'BAN' ? '封禁' : p.type === 'KICK' ? '踢出' : p.type}
                    </strong>{' '}
                    <span className="small muted">
                      {p.by === 'auto' ? '自动' : '管理员'} · {p.type === 'BAN' ? (p.minutes ? `${p.minutes} 分钟` : '永久') : ''} ·{' '}
                      {formatTime(p.at)}
                    </span>
                    <div className="small">{p.reason}</div>
                  </div>
                </div>
              ))}
            </div>
          )}
          <h3>备注</h3>
          {(c.notes ?? []).length === 0 && <p className="muted small">还没有备注。</p>}
          <div className="inventory-list">
            {(c.notes ?? []).map((n, i) => (
              <div key={i} className="inventory-row">
                <div>
                  <div style={{ whiteSpace: 'pre-wrap' }}>{n.text}</div>
                  <div className="small muted">
                    {n.by} · {formatTime(n.at)}
                  </div>
                </div>
              </div>
            ))}
          </div>
          <form className="form" onSubmit={addNote} style={{ marginTop: 12 }}>
            <textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} placeholder="例如：观看回放确认使用了飞行" />
            <div className="row end">
              <button className="btn" disabled={!note.trim()}>
                添加备注
              </button>
            </div>
          </form>
        </section>
      </div>

      <section className="card">
        <div className="row between">
          <h2>证据（最近 {evidence.length} 条）</h2>
          <select value={checkFilter} onChange={(e) => setCheckFilter(e.target.value)} style={{ width: 240 }} aria-label="按检测筛选">
            <option value="">全部检测</option>
            {topChecks(c).map(([checkId]) => (
              <option key={checkId} value={checkId}>
                {checkName(checkId, registry)}
              </option>
            ))}
          </select>
        </div>
        {shown.length === 0 ? (
          <p className="muted small">没有证据（旧证据超过每个案件的保留条数后会被删除）。</p>
        ) : (
          <div className="table-scroll" style={{ maxHeight: 640 }}>
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>检测</th>
                  <th>VL</th>
                  <th>置信度</th>
                  <th>处置</th>
                  <th>延迟</th>
                  <th>模式 / 地图</th>
                  <th>位置</th>
                  <th>细节</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((e, i) => {
                  const action = ACTIONS[e.action] ?? { label: e.action, className: '' }
                  return (
                    <tr key={`${e.at}-${i}`}>
                      <td className="small">{formatTime(e.at)}</td>
                      <td>
                        {checkName(e.check, registry)}
                        {e.review && <span className="badge badge-warn">复核</span>}
                      </td>
                      <td>{fixed(e.vl, 1)}</td>
                      <td>{percent(e.confidence)}</td>
                      <td className={action.className}>{action.label}</td>
                      <td className={`small ${(e.ping ?? 0) > 300 ? 'text-warn' : ''}`}>
                        {e.ping ?? '—'} ms ± {e.jitter ?? '—'}
                      </td>
                      <td className="small">
                        {e.modeTitle ?? e.modeId ?? <span className="muted">不在对局</span>}
                        {e.mapId && <div className="muted">{e.mapId}</div>}
                      </td>
                      <td className="small">
                        <code>
                          {fixed(e.x, 1)}, {fixed(e.y, 1)}, {fixed(e.z, 1)}
                        </code>
                        {e.world && <div className="muted">{e.world}</div>}
                      </td>
                      <td className="small">{e.detail}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {deciding && (
        <ReviewDialog
          kase={c}
          decision={deciding}
          defaultReason={config.data?.autoBan.reason}
          onClose={() => setDeciding(null)}
          onDone={(next, text) => {
            setDeciding(null)
            after(next, text)
          }}
        />
      )}
    </>
  )
}


function decisionLabel(decision: string) {
  return { BAN: '封禁', CLEAR: '确认误判', DISMISS: '不予处理', WATCH: '继续观察' }[decision] ?? decision
}

const BAN_DURATIONS: { label: string; minutes: number }[] = [
  { label: '1 天', minutes: 1440 },
  { label: '7 天', minutes: 10080 },
  { label: '30 天', minutes: 43200 },
  { label: '永久', minutes: 0 },
]

const DECISION_TEXT: Record<AntiCheatDecision, { title: string; text: string; button: string; danger?: boolean }> = {
  BAN: {
    title: '封禁',
    text: '封禁该玩家并关闭案件。在线的玩家会在 15 秒内被踢出，之后无法进入服务器（可在「用户」页解封）。',
    button: '确认封禁',
    danger: true,
  },
  CLEAR: {
    title: '确认误判',
    text: '确认这些上报是误判，关闭案件（不处罚）。之后的新上报会开新案件。若同一检测反复误判，请在「设置」中调大该检测的宽限或关闭它。',
    button: '确认误判',
  },
  DISMISS: {
    title: '不予处理',
    text: '关闭案件，不下结论也不处罚。之后的新上报会开新案件。',
    button: '不予处理',
  },
  WATCH: {
    title: '继续观察',
    text: '案件保持打开并标记为「继续观察」，继续收集证据，退出待复核状态；可疑度再次达到复核分数时会重新进入待复核。',
    button: '继续观察',
  },
}

function ReviewDialog({
  kase,
  decision,
  defaultReason,
  onClose,
  onDone,
}: {
  kase: AntiCheatCase
  decision: AntiCheatDecision
  defaultReason: string | undefined
  onClose: () => void
  onDone: (next: AntiCheatCase, text: string) => void
}) {
  const toast = useToast()
  const info = DECISION_TEXT[decision]
  const [minutes, setMinutes] = useState(0)
  const [reason, setReason] = useState('')
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      const next = await api.reviewAntiCheatCase(kase.id, {
        decision,
        ...(decision === 'BAN' ? { minutes, reason: reason.trim() || defaultReason || '使用作弊客户端' } : {}),
        ...(note.trim() ? { note: note.trim() } : {}),
      })
      onDone(next, `${info.title}：${kase.playerName}`)
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={`${info.title} · ${kase.playerName}`} onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <p>{info.text}</p>
        {decision === 'BAN' && (
          <>
            <Field label="原因" hint="显示给被封禁的玩家，后面自动加上案件编号">
              <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder={defaultReason || '使用作弊客户端'} autoFocus />
            </Field>
            <div className="field">
              <span className="field-label">时长</span>
              <div className="preset-row">
                {BAN_DURATIONS.map((d) => (
                  <button
                    type="button"
                    key={d.label}
                    className={`chip ${minutes === d.minutes ? 'chip-active' : ''}`}
                    onClick={() => setMinutes(d.minutes)}
                  >
                    {d.label}
                  </button>
                ))}
              </div>
              <div className="row">
                <NumberInput value={minutes} min={0} onChange={setMinutes} className="num" />
                <span className="muted small">分钟（0 = 永久）</span>
              </div>
            </div>
          </>
        )}
        <Field label="备注（可选）" hint="写进案件备注，便于之后查看为什么这样处理">
          <textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} />
        </Field>
        <div className="row end">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button className={info.danger ? 'btn btn-danger' : 'btn btn-primary'} disabled={busy}>
            {busy ? '处理中…' : info.button}
          </button>
        </div>
      </form>
    </Modal>
  )
}

// ---- settings -------------------------------------------------------------------------------------

const PRESET_ID = /^[a-z0-9_]{1,32}$/

function SettingsView({ registry }: { registry: AntiCheatCheckInfo[] | undefined }) {
  const toast = useToast()
  const config = useLoad(api.antiCheatConfig)
  const modes = useLoad(api.gameModes)
  const [edited, setEdited] = useState<AntiCheatConfig | null>(null)
  const [picked, setPicked] = useState<string>('standard')
  const [busy, setBusy] = useState(false)
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    if (config.data) setEdited(config.data)
  }, [config.data])

  const c = edited
  const dirty = !!c && !!config.data && JSON.stringify(c) !== JSON.stringify(config.data)
  const presetIds = Object.keys(c?.presets ?? {})
  const presetId = c && c.presets[picked] ? picked : (presetIds[0] ?? '')

  const set = (patch: Partial<AntiCheatConfig>) => setEdited((cur) => (cur ? { ...cur, ...patch } : cur))
  const setPreset = (id: string, preset: AntiCheatPreset) => setEdited((cur) => (cur ? { ...cur, presets: { ...cur.presets, [id]: preset } } : cur))

  const save = async (e: FormEvent) => {
    e.preventDefault()
    if (!c) return
    setBusy(true)
    try {
      const saved = await api.saveAntiCheatConfig(c)
      config.setData(saved)
      setEdited(saved)
      toast.ok('已保存，游戏服务器下一次同步（30 秒内）后生效')
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  /** Modes that name the preset (they fall back to the default preset once it is gone). */
  const usersOf = (id: string) =>
    (modes.data ?? []).filter((m: GameMode) => (m.settings.antiCheat as { preset?: string } | undefined)?.preset === id)

  const removePreset = (id: string) => {
    if (!c) return
    const using = usersOf(id)
    const text =
      `确定删除预设「${presetLabel(id, c.presets[id])}」？` +
      (using.length ? `\n以下模式正在使用它，保存后会改用默认预设：${using.map((m) => m.title).join('、')}` : '')
    if (!confirmAction(text)) return
    const presets = { ...c.presets }
    delete presets[id]
    set({ presets })
    setPicked(c.defaultPreset)
  }

  if (!c) return <ErrorBox error={config.error} />

  return (
    <>
    <form className="form" onSubmit={save}>
      <ErrorBox error={config.error} />
      <section className="card form">
        <h2>全局</h2>
        <div className="grid-2">
          <Field label="反作弊" hint="关闭后所有模式都不检测（玩家状态仍在跟踪）">
            <label className="check">
              <input type="checkbox" checked={c.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
              启用反作弊
            </label>
          </Field>
          <Field label="默认预设" hint="不在对局中的玩家（管理员进入世界）与预设不存在的模式使用">
            <select value={c.defaultPreset} onChange={(e) => set({ defaultPreset: e.target.value })}>
              {presetIds.map((id) => (
                <option key={id} value={id}>
                  {presetLabel(id, c.presets[id])}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <h2>自动封禁</h2>
        <label className="check">
          <input type="checkbox" checked={c.autoBan.enabled} onChange={(e) => set({ autoBan: { ...c.autoBan, enabled: e.target.checked } })} />
          允许自动封禁
        </label>
        <ul className="warnings">
          <li>
            开启后，在预设允许自动封禁的模式中，置信度达到预设的「自动封禁」阈值（且延迟不高、主要证据是可封禁的检测）时，系统不经人工直接封禁玩家。
          </li>
          <li>误封会直接影响正常玩家：建议先用「仅记录」或人工复核观察一段时间，确认各检测在你的服务器上没有误判后再开启。</li>
          <li>关闭时，达到阈值的玩家只会进入「待复核」。</li>
        </ul>
        <div className="grid-2">
          <Field label="封禁时长（分钟）" hint="0 = 永久">
            <NumberInput value={c.autoBan.durationMinutes} min={0} onChange={(v) => set({ autoBan: { ...c.autoBan, durationMinutes: v } })} />
          </Field>
          <Field label="封禁原因" hint="显示给玩家，后面自动加上案件编号">
            <input value={c.autoBan.reason} onChange={(e) => set({ autoBan: { ...c.autoBan, reason: e.target.value } })} />
          </Field>
        </div>

        <h2>提醒、证据与可疑度</h2>
        <div className="grid-3">
          <Field label="管理员提醒" hint="置信度达到预设「提醒」阈值时通知在线 OP（可用 /bl ac alerts 关闭自己的）">
            <label className="check">
              <input type="checkbox" checked={c.alerts.staff} onChange={(e) => set({ alerts: { ...c.alerts, staff: e.target.checked } })} />
              提醒在线管理员
            </label>
          </Field>
          <Field label="同一玩家提醒间隔（秒）">
            <NumberInput value={c.alerts.cooldownSeconds} min={0} onChange={(v) => set({ alerts: { ...c.alerts, cooldownSeconds: v } })} />
          </Field>
          <Field label="上报冷却（秒）" hint="同一玩家同一检测最多每隔多久上报一条证据">
            <NumberInput
              value={c.evidence.reportCooldownSeconds}
              min={0}
              onChange={(v) => set({ evidence: { ...c.evidence, reportCooldownSeconds: v } })}
            />
          </Field>
          <Field label="每个案件保留证据（条）" hint="超出后删除最旧的">
            <NumberInput value={c.evidence.maxPerCase} min={1} onChange={(v) => set({ evidence: { ...c.evidence, maxPerCase: v } })} />
          </Field>
          <Field label="已关闭案件保留（天）">
            <NumberInput value={c.evidence.retentionDays} min={1} onChange={(v) => set({ evidence: { ...c.evidence, retentionDays: v } })} />
          </Field>
          <Field label="可疑度半衰期（天）" hint="每条上报把置信度加进案件可疑度，可疑度按此衰减">
            <NumberInput
              value={c.suspicion.halfLifeDays}
              min={0.1}
              step={0.5}
              onChange={(v) => set({ suspicion: { ...c.suspicion, halfLifeDays: v } })}
            />
          </Field>
          <Field label="复核分数" hint="案件可疑度达到后进入「待复核」">
            <NumberInput
              value={c.suspicion.reviewScore}
              min={0.1}
              step={0.1}
              onChange={(v) => set({ suspicion: { ...c.suspicion, reviewScore: v } })}
            />
          </Field>
        </div>
      </section>

      <section className="card form">
        <div className="row between wrap">
          <h2 style={{ margin: 0 }}>预设</h2>
          <div className="row">
            {presetId && presetId !== 'standard' && presetId !== c.defaultPreset && (
              <button type="button" className="btn btn-ghost" onClick={() => removePreset(presetId)}>
                删除预设
              </button>
            )}
            <button type="button" className="btn" onClick={() => setCreating(true)}>
              + 新建预设
            </button>
          </div>
        </div>
        <p className="muted small">
          每个模式在「小游戏管理」中选择一个预设，并可按检测覆盖。新模式默认：枪战爆破用 strict，开放世界与练习模式用 relaxed，其他用 standard。
        </p>
        <div className="preset-row">
          {presetIds.map((id) => (
            <button type="button" key={id} className={`chip ${id === presetId ? 'chip-active' : ''}`} onClick={() => setPicked(id)}>
              {presetLabel(id, c.presets[id])}
              {id === c.defaultPreset ? ' · 默认' : ''}
              {usersOf(id).length > 0 ? ` · ${usersOf(id).length} 个模式` : ''}
            </button>
          ))}
        </div>
        {presetId && c.presets[presetId] && (
          <PresetEditor
            id={presetId}
            preset={c.presets[presetId]}
            registry={registry}
            globalAutoBan={c.autoBan.enabled}
            onChange={(p) => setPreset(presetId, p)}
          />
        )}
      </section>

      <div className="row end ac-savebar">
        {dirty && <span className="text-warn small">有未保存的修改</span>}
        <button type="button" className="btn btn-ghost" disabled={!dirty} onClick={() => config.data && setEdited(config.data)}>
          撤销修改
        </button>
        <button className="btn btn-primary" disabled={busy || !dirty}>
          {busy ? '保存中…' : '保存设置'}
        </button>
      </div>
    </form>

      {creating && (
        <NewPresetDialog
          existing={c.presets}
          copyFrom={presetId}
          onClose={() => setCreating(false)}
          onCreate={(id, preset) => {
            setPreset(id, preset)
            setPicked(id)
            setCreating(false)
          }}
        />
      )}
    </>
  )
}

function NewPresetDialog({
  existing,
  copyFrom,
  onClose,
  onCreate,
}: {
  existing: Record<string, AntiCheatPreset>
  copyFrom: string
  onClose: () => void
  onCreate: (id: string, preset: AntiCheatPreset) => void
}) {
  const [id, setId] = useState('')
  const [name, setName] = useState('')
  const [source, setSource] = useState(copyFrom)
  const problem = !PRESET_ID.test(id) ? '只能使用小写字母、数字和下划线（1-32 位）' : existing[id] ? '已存在同名预设' : null
  const create = (e: FormEvent) => {
    e.preventDefault()
    if (problem) return
    const base: AntiCheatPreset = JSON.parse(JSON.stringify(existing[source]))
    onCreate(id, { ...base, name: name.trim() || id })
  }
  return (
    <Modal title="新建预设" onClose={onClose}>
      <form className="form" onSubmit={create}>
        <div className="grid-2">
          <Field label="预设 ID" hint={id ? (problem ?? '模式设置里保存的是这个 ID') : '例如 ranked_strict'}>
            <input value={id} onChange={(e) => setId(e.target.value.toLowerCase())} autoFocus />
          </Field>
          <Field label="名称">
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="排位严格" />
          </Field>
        </div>
        <Field label="复制自">
          <select value={source} onChange={(e) => setSource(e.target.value)}>
            {Object.keys(existing).map((k) => (
              <option key={k} value={k}>
                {presetLabel(k, existing[k])}
              </option>
            ))}
          </select>
        </Field>
        <div className="row end">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary" disabled={!!problem}>
            创建
          </button>
        </div>
      </form>
    </Modal>
  )
}

function PresetEditor({
  id,
  preset,
  registry,
  globalAutoBan,
  onChange,
}: {
  id: string
  preset: AntiCheatPreset
  registry: AntiCheatCheckInfo[] | undefined
  globalAutoBan: boolean
  onChange: (preset: AntiCheatPreset) => void
}) {
  const p = preset
  const set = (patch: Partial<AntiCheatPreset>) => onChange({ ...p, ...patch })
  return (
    <div className="settings-block">
      <div className="settings-title">
        {presetLabel(id, p)}
      </div>
      <div className="grid-3">
        <Field label="名称">
          <input value={p.name} onChange={(e) => set({ name: e.target.value })} />
        </Field>
        <Field label="说明" hint="显示在模式编辑里">
          <input value={p.description} onChange={(e) => set({ description: e.target.value })} />
        </Field>
        <Field label="宽限倍率" hint="0.1–5，乘在每项检测的宽限上（> 1 更宽松）">
          <NumberInput value={p.toleranceScale} min={0.1} max={5} step={0.05} onChange={(v) => set({ toleranceScale: v })} />
        </Field>
      </div>

      <h3>延迟补偿</h3>
      <div className="grid-4">
        <Field label="最大补偿（毫秒）" hint="往返时间超过它时窗口不再变大">
          <NumberInput value={p.ping.maxCompensationMs} min={0} max={5000} onChange={(v) => set({ ping: { ...p.ping, maxCompensationMs: v } })} />
        </Field>
        <Field label="抖动倍数" hint="窗口 = 往返时间 + 倍数 × 抖动">
          <NumberInput
            value={p.ping.jitterMultiplier}
            min={0}
            max={10}
            step={0.5}
            onChange={(v) => set({ ping: { ...p.ping, jitterMultiplier: v } })}
          />
        </Field>
        <Field label="额外 tick" hint="窗口再加的 tick 数">
          <NumberInput value={p.ping.extraTicks} min={0} max={40} onChange={(v) => set({ ping: { ...p.ping, extraTicks: v } })} />
        </Field>
        <Field label="高延迟（毫秒）" hint="超过后受延迟影响的检测置信度打折、不自动封禁；回退照常">
          <NumberInput value={p.ping.highPingMs} min={50} max={5000} onChange={(v) => set({ ping: { ...p.ping, highPingMs: v } })} />
        </Field>
      </div>

      <h3>置信度阈值</h3>
      <div className="grid-3">
        <Field label="提醒" hint="通知在线管理员">
          <NumberInput
            value={p.confidence.alert}
            min={0}
            max={1}
            step={0.01}
            onChange={(v) => set({ confidence: { ...p.confidence, alert: v } })}
          />
        </Field>
        <Field label="复核" hint="上报标记为待复核">
          <NumberInput
            value={p.confidence.review}
            min={0}
            max={1}
            step={0.01}
            onChange={(v) => set({ confidence: { ...p.confidence, review: v } })}
          />
        </Field>
        <Field label="自动封禁" hint="1 = 永不自动封禁">
          <NumberInput
            value={p.confidence.autoBan}
            min={0}
            max={1}
            step={0.01}
            onChange={(v) => set({ confidence: { ...p.confidence, autoBan: v } })}
          />
        </Field>
      </div>

      <h3>处置</h3>
      <div className="row wrap" style={{ gap: 24 }}>
        <label className="check">
          <input type="checkbox" checked={p.actions.setback} onChange={(e) => set({ actions: { ...p.actions, setback: e.target.checked } })} />
          回退 / 取消违规动作
        </label>
        <label className="check">
          <input type="checkbox" checked={p.actions.kick} onChange={(e) => set({ actions: { ...p.actions, kick: e.target.checked } })} />
          VL 达到上限时踢出
        </label>
        <label className="check">
          <input type="checkbox" checked={p.actions.autoBan} onChange={(e) => set({ actions: { ...p.actions, autoBan: e.target.checked } })} />
          允许自动封禁
        </label>
      </div>
      {p.actions.autoBan && !globalAutoBan && <p className="muted small">全局「允许自动封禁」未开启，该预设目前只会把玩家送进待复核。</p>}
      {!p.actions.setback && !p.actions.kick && !p.actions.autoBan && (
        <p className="muted small">该预设只记录证据，不做任何处置。</p>
      )}

      <h3>检测</h3>
      <p className="muted small">留空 = 使用检测的默认值（灰色数字）；有改动的行高亮。鼠标停在检测名上可看说明。</p>
      <CheckOverridesTable
        registry={registry}
        value={p.checks ?? {}}
        onChange={(checks) => set({ checks })}
        base={(checkId) => resolveCheck(checkId, registry)}
        inheritLabel="默认"
      />
    </div>
  )
}
