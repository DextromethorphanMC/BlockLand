import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api'
import { confirmAction, Empty, ErrorBox, Field, NumberInput, PageHeader, useLoad, useToast } from '../components/ui'
import { categoryLabel } from '../components/Ranks'
import type { CheckInConfig, CheckInReward } from '../types'

const MAX_DAYS = 31
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
                    <td>
                      <input
                        list="checkin-entitlements"
                        value={d.entitlement}
                        placeholder="留空 = 只记签到"
                        onChange={(e) => {
                          const v = e.target.value
                          setDay(i, { entitlement: v.includes(':') ? v.toLowerCase() : v.toUpperCase() })
                        }}
                      />
                      {d.entitlement && itemName(d.entitlement) && <div className="small muted">{itemName(d.entitlement)}</div>}
                    </td>
                    <td>
                      <input
                        value={d.label}
                        maxLength={40}
                        placeholder={itemName(d.entitlement) ?? '游戏内显示物品名称'}
                        onChange={(e) => setDay(i, { label: e.target.value })}
                      />
                    </td>
                    <td>
                      <NumberInput value={d.durationDays} min={0} max={3650} onChange={(v) => setDay(i, { durationDays: v })} />
                      <div className="small muted">{d.durationDays > 0 ? `${d.durationDays} 天（重复获得会顺延）` : '永久'}</div>
                    </td>
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
          <div className="row between">
            <button type="button" className="btn btn-ghost" disabled={c.days.length >= MAX_DAYS} onClick={add}>
              添加一天
            </button>
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
