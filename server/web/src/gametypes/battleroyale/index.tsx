import { Field, NumberInput } from '../../components/ui'
import { MapPoolField } from '../common/MapPoolField'
import { MovementFields } from '../common/Movement'
import type { GameTypeUi, SettingsFormProps } from '../index'
import { BattleRoyaleMapPanel, useBattleRoyaleOverlay } from './BattleRoyaleMapPanel'
import { LootTableField } from './LootTableField'
import { BATTLE_ROYALE, readSettings, readSpots, type BattleRoyaleSettings } from './model'

const PRESETS = [2, 8, 16, 32]

function BattleRoyaleSettingsForm({ settings, onChange, maps }: SettingsFormProps) {
  const s = readSettings(settings)
  const set = (patch: Partial<BattleRoyaleSettings>) => onChange({ ...settings, ...s, ...patch })

  return (
    <div className="settings-block">
      <div className="settings-title">大逃杀设置</div>
      <div className="preset-row">
        {PRESETS.map((n) => (
          <button type="button" key={n} className={`chip ${s.players === n ? 'chip-active' : ''}`} onClick={() => set({ players: n })}>
            {n} 人
          </button>
        ))}
      </div>
      <div className="grid-3">
        <Field label="每局人数" hint="凑满该人数才开局，各自为战（2-64）">
          <NumberInput value={s.players} min={2} max={64} onChange={(v) => set({ players: Math.round(v) })} />
        </Field>
        <Field label="开局倒计时（秒）">
          <NumberInput value={s.countdownSeconds} min={0} max={120} onChange={(v) => set({ countdownSeconds: Math.round(v) })} />
        </Field>
        <Field label="结算展示（秒）">
          <NumberInput value={s.endingSeconds} min={1} max={60} onChange={(v) => set({ endingSeconds: Math.round(v) })} />
        </Field>
        <Field label="开场动画（秒）" hint="镜头飞越岛屿的开场动画，0 = 直接登机（0-20）">
          <NumberInput value={s.introSeconds} min={0} max={20} onChange={(v) => set({ introSeconds: Math.round(v) })} />
        </Field>
        <Field label="飞机高度" hint="地图最高处之上多少格（20-120）">
          <NumberInput value={s.planeHeight} min={20} max={120} onChange={(v) => set({ planeHeight: Math.round(v) })} />
        </Field>
        <Field label="飞机速度（格/秒）" hint="4-40">
          <NumberInput value={s.planeSpeed} min={4} max={40} step={0.5} onChange={(v) => set({ planeSpeed: v })} />
        </Field>
        <Field label="自动开伞高度" hint="离地多少格时降落伞自动打开，0 = 不自动（0-100）">
          <NumberInput value={s.autoDeployHeight} min={0} max={100} onChange={(v) => set({ autoDeployHeight: Math.round(v) })} />
        </Field>
      </div>
      <p className="muted small">
        开局流程：倒计时 → 开场动画 → 所有人乘飞机沿一条穿过岛屿中心的随机航线飞行，按空格跳下（航线结束时仍在机上的玩家被自动投下）→
        自由落体中再按空格打开降落伞（每人开局自动获得一个，穿在胸甲栏）→ 落地搜刮 → 最后存活的玩家获胜。
      </p>

      <div className="settings-title">安全区</div>
      <label className="check">
        <input type="checkbox" checked={s.zoneEnabled} onChange={(e) => set({ zoneEnabled: e.target.checked })} />
        启用缩小的安全区
      </label>
      {s.zoneEnabled && (
        <div className="grid-3">
          <Field label="开始收缩（秒）" hint="跳伞开始后多久开始收缩（0-600）">
            <NumberInput value={s.zoneDelaySeconds} min={0} max={600} onChange={(v) => set({ zoneDelaySeconds: Math.round(v) })} />
          </Field>
          <Field label="收缩时长（秒）" hint="从覆盖整张地图缩到最终大小（10-1800）">
            <NumberInput value={s.zoneShrinkSeconds} min={10} max={1800} onChange={(v) => set({ zoneShrinkSeconds: Math.round(v) })} />
          </Field>
          <Field label="最终半径（格）" hint="0-200">
            <NumberInput value={s.zoneFinalRadius} min={0} max={200} onChange={(v) => set({ zoneFinalRadius: Math.round(v) })} />
          </Field>
          <Field label="区外伤害（每秒）" hint="0-20">
            <NumberInput value={s.zoneDamage} min={0} max={20} step={0.5} onChange={(v) => set({ zoneDamage: v })} />
          </Field>
        </div>
      )}

      <div className="settings-title">物资</div>
      <div className="grid-3">
        <Field label="每个物资点最少物品数" hint="0-10">
          <NumberInput value={s.lootPerSpot.min} min={0} max={10} onChange={(v) => set({ lootPerSpot: { ...s.lootPerSpot, min: Math.round(v) } })} />
        </Field>
        <Field label="每个物资点最多物品数" hint={s.lootPerSpot.min > s.lootPerSpot.max ? <span className="text-warn">不能小于最少物品数</span> : '0-10'}>
          <NumberInput value={s.lootPerSpot.max} min={0} max={10} onChange={(v) => set({ lootPerSpot: { ...s.lootPerSpot, max: Math.round(v) } })} />
        </Field>
        <Field label="自动物资点数量" hint="地图没有设置物资点时，在地表随机撒放的数量（0-400）">
          <NumberInput value={s.autoLootSpots} min={0} max={400} onChange={(v) => set({ autoLootSpots: Math.round(v) })} />
        </Field>
      </div>
      <LootTableField loot={s.loot} onChange={(loot) => set({ loot })} />

      <MapPoolField
        gameType={BATTLE_ROYALE}
        maps={maps}
        mapIds={s.mapIds}
        onChange={(mapIds) => set({ mapIds })}
        hint="不勾选任何地图 = 使用该类型的全部地图。物资点在「地图管理」中设置。"
        describe={(map) => {
          const spots = readSpots(map.settings).length
          if (spots > 0) return { ok: true, text: `${spots} 个物资点` }
          return { ok: s.autoLootSpots > 0, text: s.autoLootSpots > 0 ? `无物资点 · 随机撒放 ${s.autoLootSpots} 处` : '无物资点' }
        }}
      />

      <div className="row wrap" style={{ gap: 24 }}>
        <label className="check">
          <input type="checkbox" checked={s.allowBlockBreak} onChange={(e) => set({ allowBlockBreak: e.target.checked })} />
          允许破坏方块
        </label>
        <label className="check">
          <input type="checkbox" checked={s.allowBlockPlace} onChange={(e) => set({ allowBlockPlace: e.target.checked })} />
          允许放置方块
        </label>
      </div>
      <MovementFields settings={settings} onChange={onChange} />
      <p className="muted small">下方的开局装备可留空（玩家落地后靠搜刮）；降落伞由游戏另行发放。</p>
    </div>
  )
}

export const battleRoyaleUi: GameTypeUi = {
  formatLabel: (settings) => `${readSettings(settings).players} 人`,
  SettingsForm: BattleRoyaleSettingsForm,
  MapPanel: BattleRoyaleMapPanel,
  useMapOverlay: useBattleRoyaleOverlay,
  timeLimitHint: '可选的总时限：到时仍有多人存活则平局；不限制 = 打到只剩一人',
}
