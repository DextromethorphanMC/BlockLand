import { useEffect, useState, type FormEvent } from 'react'
import { api } from '../api'
import { Empty, ErrorBox, Field, Modal, NumberInput, PageHeader, useLoad, useToast } from '../components/ui'
import { TIERS, TierBadge } from '../components/Ranks'
import type { RankedConfig } from '../types'

/** 排位: how scores and tiers work, the ladder, and the season reset. Which modes are ranked is set on each mode. */
export function Ranked() {
  const toast = useToast()
  const config = useLoad(api.rankedConfig)
  const ladder = useLoad(() => api.rankedLeaderboard(100))
  const [edited, setEdited] = useState<RankedConfig | null>(null)
  const [busy, setBusy] = useState(false)
  const [confirmReset, setConfirmReset] = useState(false)

  useEffect(() => {
    if (config.data) setEdited(config.data)
  }, [config.data])

  const set = (patch: Partial<RankedConfig>) => setEdited((c) => (c ? { ...c, ...patch } : c))

  const save = async (e: FormEvent) => {
    e.preventDefault()
    if (!edited) return
    setBusy(true)
    try {
      await api.saveRankedConfig(edited)
      toast.ok('已保存')
      config.reload()
      ladder.reload()
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  const reset = async () => {
    try {
      const next = await api.resetRankedSeason()
      toast.ok(`已开始第 ${next.season} 赛季`)
      setConfirmReset(false)
      config.reload()
      ladder.reload()
    } catch (err) {
      toast.error(err)
    }
  }

  const c = edited
  return (
    <>
      <PageHeader
        title="排位"
        subtitle={c ? `第 ${c.season} 赛季。在「小游戏管理」中勾选「排位模式」的模式，对局结果计入全服统一的排位分。` : undefined}
        actions={
          <button className="btn btn-danger" onClick={() => setConfirmReset(true)}>
            开始新赛季
          </button>
        }
      />
      <ErrorBox error={config.error ?? ladder.error} />
      {c && (
        <form className="card form" onSubmit={save}>
          <h2>计分规则</h2>
          <div className="grid-3">
            <Field label="初始分数" hint="新玩家与新赛季的起点">
              <NumberInput value={c.startScore} min={0} max={10000} onChange={(v) => set({ startScore: v })} />
            </Field>
            <Field label="K 值" hint="每局最多变动的分数（双方实力相当时变动约一半）">
              <NumberInput value={c.kFactor} min={1} max={200} onChange={(v) => set({ kFactor: v })} />
            </Field>
            <Field label="定级赛场数" hint="前几局使用定级赛 K 值，分数更快到位">
              <NumberInput value={c.placementMatches} min={0} max={50} onChange={(v) => set({ placementMatches: v })} />
            </Field>
            <Field label="定级赛 K 值">
              <NumberInput value={c.placementK} min={1} max={400} onChange={(v) => set({ placementK: v })} />
            </Field>
          </div>
          <h2>段位分数线</h2>
          <div className="grid-3">
            <Field label={`${TIERS[1].label} ≥`}>
              <NumberInput value={c.iron} min={0} onChange={(v) => set({ iron: v })} />
            </Field>
            <Field label={`${TIERS[2].label} ≥`}>
              <NumberInput value={c.gold} min={0} onChange={(v) => set({ gold: v })} />
            </Field>
            <Field label={`${TIERS[3].label} ≥`}>
              <NumberInput value={c.diamond} min={0} onChange={(v) => set({ diamond: v })} />
            </Field>
            <Field label={`${TIERS[4].label} ≥`} hint="达到分数且排名在名额内才是猎首，否则为钻石">
              <NumberInput value={c.headhunter} min={0} onChange={(v) => set({ headhunter: v })} />
            </Field>
            <Field label="猎首名额" hint="全服排名前多少名">
              <NumberInput value={c.headhunterTop} min={1} max={10000} onChange={(v) => set({ headhunterTop: v })} />
            </Field>
          </div>
          <p className="muted small">
            低于 {c.iron} 分为{TIERS[0].label}。团队对局按双方平均分计算期望胜率（Elo），大逃杀等多方对局按名次两两比较。中途离开的玩家按失败计算（由游戏服务器上报）。
          </p>
          <div className="row end">
            <button className="btn btn-primary" disabled={busy}>
              {busy ? '保存中…' : '保存'}
            </button>
          </div>
        </form>
      )}

      <section className="card">
        <h2>排行榜（前 100）</h2>
        {ladder.data?.length === 0 && <Empty>本赛季还没有人打过排位赛。</Empty>}
        {ladder.data && ladder.data.length > 0 && (
          <table className="table table-compact">
            <thead>
              <tr>
                <th>#</th>
                <th>玩家</th>
                <th>分数</th>
                <th>段位</th>
                <th>场次</th>
                <th>胜场</th>
              </tr>
            </thead>
            <tbody>
              {ladder.data.map((e) => (
                <tr key={e.uuid}>
                  <td>{e.position}</td>
                  <td>
                    <strong>{e.name ?? '—'}</strong>
                    <div className="small muted">
                      <code>{e.uuid}</code>
                    </div>
                  </td>
                  <td>{e.score}</td>
                  <td>
                    <TierBadge tier={e.tier} />
                  </td>
                  <td>{e.matches}</td>
                  <td>{e.wins}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      {confirmReset && c && (
        <Modal title="开始新赛季" onClose={() => setConfirmReset(false)}>
          <p>
            所有玩家的排位分将重置为 {c.startScore}，场次清零，排行榜清空；每位玩家第 {c.season} 赛季的最终分数、段位与排名会保留在记录中。此操作无法撤销。
          </p>
          <div className="row end">
            <button className="btn btn-ghost" onClick={() => setConfirmReset(false)}>
              取消
            </button>
            <button className="btn btn-danger" onClick={reset}>
              确认重置，开始第 {c.season + 1} 赛季
            </button>
          </div>
        </Modal>
      )}
    </>
  )
}
