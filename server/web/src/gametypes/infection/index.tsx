import { Field, NumberInput, useLoad } from '../../components/ui'
import { MapPoolField } from '../common/MapPoolField'
import { MovementFields } from '../common/Movement'
import type { GameTypeUi, SettingsFormProps } from '../index'
import { InfectionMapPanel, useInfectionOverlay } from './InfectionMapPanel'
import { MonsterPicker } from './MonsterPicker'
import { INFECTION, effectiveSpawns, forHumans, forMonsters, infectionRegistry, publishedMonsters, readSettings, type InfectionSettings } from './model'

function InfectionSettingsForm({ settings, onChange, maps }: SettingsFormProps) {
  const s = readSettings(settings)
  const set = (patch: Partial<InfectionSettings>) => onChange({ ...settings, ...s, ...patch })
  const registry = useLoad(infectionRegistry)
  const monsters = publishedMonsters(registry.data)
  const humans = s.players - s.startMonsters
  const toWin = Math.floor(s.rounds / 2) + 1

  return (
    <div className="settings-block">
      <div className="settings-title">枪战感染设置</div>
      <div className="grid-3">
        <Field label="每局人数" hint="凑满该人数才开局">
          <NumberInput value={s.players} min={2} max={32} onChange={(v) => set({ players: v })} />
        </Field>
        <Field
          label="开局怪物人数"
          hint={
            s.startMonsters < s.players ? (
              `每回合 ${s.startMonsters} 名母体 vs ${humans} 名人类`
            ) : (
              <span className="text-warn">必须小于每局人数</span>
            )
          }
        >
          <NumberInput value={s.startMonsters} min={1} max={Math.max(1, Math.min(31, s.players - 1))} onChange={(v) => set({ startMonsters: v })} />
        </Field>
        <Field label="回合数" hint={`先赢 ${toWin} 回合的一方获胜（1-15，建议奇数）`}>
          <NumberInput value={s.rounds} min={1} max={15} onChange={(v) => set({ rounds: v })} />
        </Field>
        <Field label="回合间隔（秒）" hint="两回合之间的休息时间（3-30）">
          <NumberInput value={s.roundBreakSeconds} min={3} max={30} onChange={(v) => set({ roundBreakSeconds: v })} />
        </Field>
        <Field label="开局倒计时（秒）">
          <NumberInput value={s.countdownSeconds} min={0} max={120} onChange={(v) => set({ countdownSeconds: v })} />
        </Field>
        <Field label="结算展示（秒）">
          <NumberInput value={s.endingSeconds} min={1} max={60} onChange={(v) => set({ endingSeconds: v })} />
        </Field>
        <Field label="母体出现时间（秒）" hint="每回合开始后多久选出母体（3-60）">
          <NumberInput value={s.infectionDelaySeconds} min={3} max={60} onChange={(v) => set({ infectionDelaySeconds: v })} />
        </Field>
        <Field label="母体生命倍率" hint="母体血量 = 怪物血量 × 倍率（1-5）">
          <NumberInput value={s.motherHealthMultiplier} min={1} max={5} step={0.1} onChange={(v) => set({ motherHealthMultiplier: v })} />
        </Field>
        <Field label="怪物伤害" hint="所有怪物左键伤害相同（1-40）">
          <NumberInput value={s.monsterDamage} min={1} max={40} step={0.5} onChange={(v) => set({ monsterDamage: v })} />
        </Field>
        <Field label="怪物复活时间（秒）" hint="怪物被击杀后多久复活（1-30）">
          <NumberInput value={s.monsterRespawnSeconds} min={1} max={30} onChange={(v) => set({ monsterRespawnSeconds: v })} />
        </Field>
        <Field label="怪物选择时间（秒）" hint="超时自动使用默认怪物（3-30）">
          <NumberInput value={s.selectSeconds} min={3} max={30} onChange={(v) => set({ selectSeconds: v })} />
        </Field>
      </div>
      <p className="muted small">
        赛制：共 {s.rounds} 回合，每回合所有人重新以人类开局，{s.infectionDelaySeconds} 秒后随机 {s.startMonsters} 名玩家成为母体。
        人类坚持到「存活时间」结束赢下该回合，感染全部人类则怪物赢下该回合；赢得回合多的一方获胜，一方已无法被追上时提前结束。
      </p>

      <MapPoolField
        gameType={INFECTION}
        maps={maps}
        mapIds={s.mapIds}
        onChange={(mapIds) => set({ mapIds })}
        hint="不勾选任何地图 = 使用该类型的全部地图。出生点在「地图管理」中按角色（人类 / 怪物 / 通用）设置。"
        describe={(map) => {
          const spawns = effectiveSpawns(map)
          const humanPoints = spawns.filter(forHumans).length
          const monsterPoints = spawns.filter(forMonsters).length
          if (humanPoints === 0) return { ok: false, text: '没有人类出生点' }
          const notes = [`${humanPoints} 个人类出生点`, monsterPoints ? `${monsterPoints} 个怪物复活点` : '无怪物复活点（使用人类出生点）']
          if (humanPoints < s.players) notes.push(`少于 ${s.players} 人，将共用`)
          return { ok: humanPoints >= s.players && monsterPoints > 0, text: notes.join(' · ') }
        }}
      />

      <MonsterPicker value={s.monsters} published={monsters} onChange={(ids) => set({ monsters: ids })} />
      <MovementFields settings={settings} onChange={onChange} note="只有人类可以滑铲。" />
      <p className="muted small">开局装备在下方「开局装备」中配置；玩家可以在游戏大厅中调整自己的装备布局。</p>
    </div>
  )
}

export const infectionUi: GameTypeUi = {
  formatLabel: (settings) => {
    const s = readSettings(settings)
    return `${s.players} 人 · ${s.rounds} 回合`
  },
  SettingsForm: InfectionSettingsForm,
  MapPanel: InfectionMapPanel,
  useMapOverlay: useInfectionOverlay,
  timeLimitHint: '每回合的存活时间：人类坚持到时间结束即赢下该回合；不限制时每回合按 180 秒计算',
}
