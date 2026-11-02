import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api'
import { confirmAction, Empty, ErrorBox, Field, NumberInput, PageHeader, useLoad, useToast } from '../components/ui'
import { categoryLabel } from '../components/Ranks'
import type { CheckInConfig, CheckInMilestone, CheckInMilestoneKind, CheckInReward } from '../types'

const MAX_DAYS = 31
const MAX_MILESTONES = 64
const MAX_MILESTONE_REWARDS = 8
const KIND_NAMES: Record<CheckInMilestoneKind, string> = { STREAK: '连续签到', TOTAL: '累计签到', DATE: '指定日期' }
/** A leap year, so a yearly 02-29 still fits the date picker. */
const YEARLY_PICKER_YEAR = 2028
const ZONES = ['Asia/Shanghai', 'Asia/Hong_Kong', 'Asia/Taipei', 'Asia/Tokyo', 'Asia/Singapore', 'Europe/London', 'America/New_York', 'UTC']

/**
 * 每日签到: the cycle of daily rewards players check in for in the lobby, and who checked in lately. Consecutive days
 * walk the cycle (and start it again after its last day); a missed day breaks the streak and starts it from day one.
 */
export function CheckIn() {
  const toast = useToast()
  const config = useLoad(api.checkInConfig)
  const stats = useLoad(api.checkInStats)
  const registry = useLoad(api.inventoryRegistry)
  const [edited, setEdited] = useState<CheckInConfig | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (config.data) setEdited(config.data)
  }, [config.data])

  const set = (patch: Partial<CheckInConfig>) => setEdited((c) => (c ? { ...c, ...patch } : c))
  const setDay = (i: number, patch: Partial<CheckInReward>) =>
    setEdited((c) => (c ? { ...c, days: c.days.map((d, k) => (k === i ? { ...d, ...patch } : d)) } : c))
  const move = (i: number, by: number) =>
    setEdited((c) => {
      if (!c || i + by < 0 || i + by >= c.days.length) return c
      const days = [...c.days]
      ;[days[i], days[i + by]] = [days[i + by], days[i]]
      return { ...c, days }
    })
  const remove = (i: number) => setEdited((c) => (c ? { ...c, days: c.days.filter((_, k) => k !== i) } : c))
  const add = () => setEdited((c) => (c ? { ...c, days: [...c.days, { label: '', entitlement: '', durationDays: 0 }] } : c))
  const setMilestone = (i: number, patch: Partial<CheckInMilestone>) =>
    setEdited((c) => (c ? { ...c, milestones: c.milestones.map((m, k) => (k === i ? { ...m, ...patch } : m)) } : c))
  const removeMilestone = (i: number) => setEdited((c) => (c ? { ...c, milestones: c.milestones.filter((_, k) => k !== i) } : c))
  const addMilestone = (kind: CheckInMilestoneKind) =>
    setEdited((c) =>
      c
        ? {
            ...c,
            milestones: [
              ...c.milestones,
              {
                id: `${kind.toLowerCase()}-${Date.now().toString(36)}`,
                kind,
                name: '',
                days: kind === 'DATE' ? 0 : 7,
                date: kind === 'DATE' ? new Date().toISOString().slice(0, 10) : '',
                repeat: false,
                enabled: true,
                rewards: [{ label: '', entitlement: '', durationDays: 0 }],
              },
            ],
          }
        : c
    )

  const itemName = (key: string) => registry.data?.find((i) => i.key === key)?.name

  const save = async (e: FormEvent) => {
    e.preventDefault()
    if (!edited) return
    setBusy(true)
    try {
      await api.saveCheckInConfig(edited)
      toast.ok('已保存')
      config.reload()
      stats.reload()
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  const reset = async (uuid: string, name: string) => {
    if (!confirmAction(`清除 ${name} 的签到记录？连续天数从头开始，已发放的奖励保留。`)) return
    try {
      await api.resetCheckIn(uuid)
      toast.ok('已清除')
      stats.reload()
    } catch (err) {
      toast.error(err)
    }
  }

  const c = edited
  const s = stats.data
  return (
    <>
      <PageHeader
        title="每日签到"
        subtitle="玩家在大厅顶栏点「签到」领取当天的奖励。连续签到依次领取周期中的每一天，领完最后一天后从第 1 天重新开始；中断一天则连续天数清零，下次签到从第 1 天开始。奖励与商店购买一样记为玩家的权益（库存物品或特权）。"
      />
      <ErrorBox error={config.error ?? stats.error} />
      {c && (
        <form className="card form" onSubmit={save}>
          <div className="grid-3">
            <Field label="状态">
              <label className="check">
                <input type="checkbox" checked={c.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
                允许签到
              </label>
            </Field>
            <Field label="时区" hint="该时区的零点开始新的一天">
              <input list="checkin-zones" value={c.zone} onChange={(e) => set({ zone: e.target.value })} required />
            </Field>
          </div>
          <datalist id="checkin-zones">
            {ZONES.map((z) => (
              <option key={z} value={z} />
            ))}
          </datalist>

          <h2>签到周期（{c.days.length} 天）</h2>
          {c.days.length === 0 && <Empty>周期至少需要 1 天。</Empty>}
          {c.days.length > 0 && (
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>天</th>
                  <th>奖励</th>
                  <th>名称</th>
                  <th>有效期（天）</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {c.days.map((d, i) => (
                  <tr key={i}>
                    <td>
                      <strong>第 {i + 1} 天</strong>
                      {i === c.days.length - 1 && <div className="small muted">周期大奖</div>}
                    </td>
                    <RewardCells reward={d} itemName={itemName} empty="留空 = 只记签到" onChange={(patch) => setDay(i, patch)} />
                    <td>
                      <div className="row end">
                        <button type="button" className="btn btn-ghost" disabled={i === 0} onClick={() => move(i, -1)} title="上移">
                          ↑
                        </button>
                        <button
                          type="button"
                          className="btn btn-ghost"
                          disabled={i === c.days.length - 1}
                          onClick={() => move(i, 1)}
                          title="下移"
                        >
                          ↓
                        </button>
                        <button type="button" className="btn btn-danger" disabled={c.days.length <= 1} onClick={() => remove(i)}>
                          删除
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <datalist id="checkin-entitlements">
            <option value="PRIORITY_QUEUE">优先匹配排队</option>
            {(registry.data ?? [])
              .filter((i) => !i.free)
              .map((i) => (
                <option key={i.key} value={i.key}>
                  {categoryLabel(i)} · {i.name}
                </option>
              ))}
          </datalist>
          <p className="muted small">
            名称留空时游戏内显示物品自己的名称（随玩家语言）；没有奖励的日子显示「第 N 天」。周期变短后，正在进行中的玩家按新的周期继续。
          </p>
          <div className="row">
            <button type="button" className="btn btn-ghost" disabled={c.days.length >= MAX_DAYS} onClick={add}>
              添加一天
            </button>
          </div>

          <h2>额外奖励（{c.milestones.length}）</h2>
          <p className="muted small">
            在每天的周期奖励之外另发：<strong>连续签到</strong>满 N 天、<strong>累计签到</strong>满 N 天，或在<strong>指定日期</strong>签到。
            连续 / 累计的奖励每位玩家只发一次（新加的奖励对已超过天数的玩家在下次签到时补发）；勾选「每满 N 天重复」后，每到 N 的倍数都会再发，连续签到中断后重新累积也会再发。
            指定日期可以是某一天（只在那天发），也可以是每年的同一天。
          </p>
          {c.milestones.length === 0 && <Empty>还没有额外奖励。</Empty>}
          {c.milestones.map((m, i) => (
            <MilestoneEditor
              key={m.id + ':' + i}
              milestone={m}
              itemName={itemName}
              onChange={(patch) => setMilestone(i, patch)}
              onRemove={() => removeMilestone(i)}
            />
          ))}
          <div className="row between">
            <div className="row">
              {(['STREAK', 'TOTAL', 'DATE'] as const).map((kind) => (
                <button
                  key={kind}
                  type="button"
                  className="btn btn-ghost"
                  disabled={c.milestones.length >= MAX_MILESTONES}
                  onClick={() => addMilestone(kind)}
                >
                  添加{KIND_NAMES[kind]}奖励
                </button>
              ))}
            </div>
            <button className="btn btn-primary" disabled={busy}>
              {busy ? '保存中…' : '保存'}
            </button>
          </div>
        </form>
      )}

      <section className="card">
        <div className="row between">
          <h2>最近签到</h2>
          {s && (
            <span className="muted small">
              {s.today}：今日 {s.claimedToday} 人签到 · 累计 {s.players} 人签到过
            </span>
          )}
        </div>
        {s?.recent.length === 0 && <Empty>还没有玩家签到。</Empty>}
        {s && s.recent.length > 0 && (
          <table className="table table-compact">
            <thead>
              <tr>
                <th>玩家</th>
                <th>上次签到</th>
                <th>连续</th>
                <th>最长连续</th>
                <th>累计</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {s.recent.map((p) => (
                <tr key={p.uuid}>
                  <td>
                    <strong>{p.name}</strong>
                    <div className="small muted">
                      <code>{p.uuid}</code>
                    </div>
                  </td>
                  <td>
                    {p.lastDay ?? '—'}
                    {p.claimedToday && <div className="small muted">今天已签到</div>}
                  </td>
                  <td>{p.streak} 天</td>
                  <td>{p.best} 天</td>
                  <td>{p.total} 天</td>
                  <td>
                    <div className="row end">
                      <button className="btn btn-ghost" onClick={() => reset(p.uuid, p.name)}>
                        清除记录
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  )
}

/** The three cells of one reward (what it grants, its name, how long), for the cycle's days and the milestones alike. */
function RewardCells({
  reward,
  itemName,
  empty,
  onChange,
}: {
  reward: CheckInReward
  itemName: (key: string) => string | undefined
  empty: string
  onChange: (patch: Partial<CheckInReward>) => void
}) {
  return (
    <>
      <td>
        <input
          list="checkin-entitlements"
          value={reward.entitlement}
          placeholder={empty}
          onChange={(e) => {
            const v = e.target.value
            onChange({ entitlement: v.includes(':') ? v.toLowerCase() : v.toUpperCase() })
          }}
        />
        {reward.entitlement && itemName(reward.entitlement) && <div className="small muted">{itemName(reward.entitlement)}</div>}
      </td>
      <td>
        <input
          value={reward.label}
          maxLength={40}
          placeholder={itemName(reward.entitlement) ?? '游戏内显示物品名称'}
          onChange={(e) => onChange({ label: e.target.value })}
        />
      </td>
      <td>
        <NumberInput value={reward.durationDays} min={0} max={3650} onChange={(v) => onChange({ durationDays: v })} />
        <div className="small muted">{reward.durationDays > 0 ? `${reward.durationDays} 天（重复获得会顺延）` : '永久'}</div>
      </td>
    </>
  )
}

/** What a milestone's rule says, as the list shows it (the game words it the same way when the name is blank). */
function ruleText(m: CheckInMilestone) {
  if (m.kind === 'DATE') return m.date.length === 5 ? `每年 ${m.date} 签到` : `${m.date} 签到`
  const what = m.kind === 'STREAK' ? '连续签到' : '累计签到'
  return m.repeat ? `${what}每满 ${m.days} 天` : `${what}满 ${m.days} 天`
}

/** One extra reward: its rule (kind, count or date, repeating), name, on/off and the rewards it grants. */
function MilestoneEditor({
  milestone: m,
  itemName,
  onChange,
  onRemove,
}: {
  milestone: CheckInMilestone
  itemName: (key: string) => string | undefined
  onChange: (patch: Partial<CheckInMilestone>) => void
  onRemove: () => void
}) {
  const yearly = m.kind === 'DATE' && m.date.length === 5
  const setReward = (k: number, patch: Partial<CheckInReward>) =>
    onChange({ rewards: m.rewards.map((r, j) => (j === k ? { ...r, ...patch } : r)) })
  return (
    <div className={`milestone${m.enabled ? '' : ' milestone-off'}`}>
      <div className="row between">
        <strong>
          {KIND_NAMES[m.kind]} · {m.name || ruleText(m)}
        </strong>
        <div className="row end">
          <label className="check">
            <input type="checkbox" checked={m.enabled} onChange={(e) => onChange({ enabled: e.target.checked })} />
            启用
          </label>
          <button type="button" className="btn btn-danger" onClick={() => confirmAction(`删除「${m.name || ruleText(m)}」？`) && onRemove()}>
            删除
          </button>
        </div>
      </div>
      <div className="grid-4">
        <Field label="类型">
          <select
            value={m.kind}
            onChange={(e) => {
              const kind = e.target.value as CheckInMilestoneKind
              onChange(
                kind === 'DATE'
                  ? { kind, date: m.date || new Date().toISOString().slice(0, 10), repeat: false }
                  : { kind, days: m.days > 0 ? m.days : 7 }
              )
            }}
          >
            {(['STREAK', 'TOTAL', 'DATE'] as const).map((k) => (
              <option key={k} value={k}>
                {KIND_NAMES[k]}
              </option>
            ))}
          </select>
        </Field>
        {m.kind === 'DATE' ? (
          <>
            <Field label="日期" hint={yearly ? '每年这一天' : '只在这一天'}>
              <input
                type="date"
                required
                value={yearly ? `${YEARLY_PICKER_YEAR}-${m.date}` : m.date}
                onChange={(e) => {
                  const v = e.target.value
                  if (v) onChange({ date: yearly ? v.slice(5) : v })
                }}
              />
            </Field>
            <Field label="重复">
              <label className="check">
                <input
                  type="checkbox"
                  checked={yearly}
                  onChange={(e) =>
                    onChange({ date: e.target.checked ? m.date.slice(-5) : `${new Date().getFullYear()}-${m.date.slice(-5)}` })
                  }
                />
                每年
              </label>
            </Field>
          </>
        ) : (
          <>
            <Field label="天数" hint={m.kind === 'STREAK' ? '中断一天即从头算' : '不必连续'}>
              <NumberInput value={m.days} min={1} max={3650} onChange={(v) => onChange({ days: v })} />
            </Field>
            <Field label="重复">
              <label className="check">
                <input type="checkbox" checked={m.repeat} onChange={(e) => onChange({ repeat: e.target.checked })} />
                每满 {m.days} 天重复
              </label>
            </Field>
          </>
        )}
        <Field label="名称" hint={`留空显示「${ruleText(m)}」`}>
          <input value={m.name} maxLength={40} onChange={(e) => onChange({ name: e.target.value })} />
        </Field>
      </div>
      <table className="table table-compact">
        <thead>
          <tr>
            <th>奖励</th>
            <th>名称</th>
            <th>有效期（天）</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {m.rewards.map((r, k) => (
            <tr key={k}>
              <RewardCells reward={r} itemName={itemName} empty="物品或特权" onChange={(patch) => setReward(k, patch)} />
              <td>
                <div className="row end">
                  <button
                    type="button"
                    className="btn btn-ghost"
                    disabled={m.rewards.length <= 1}
                    onClick={() => onChange({ rewards: m.rewards.filter((_, j) => j !== k) })}
                  >
                    移除
                  </button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="row between">
        <button
          type="button"
          className="btn btn-ghost"
          disabled={m.rewards.length >= MAX_MILESTONE_REWARDS}
          onClick={() => onChange({ rewards: [...m.rewards, { label: '', entitlement: '', durationDays: 0 }] })}
        >
          添加奖励
        </button>
        <span className="small muted">
          标识 <code>{m.id}</code>
        </span>
      </div>
    </div>
  )
}
