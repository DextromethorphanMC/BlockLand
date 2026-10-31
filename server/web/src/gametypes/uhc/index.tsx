import { Field, NumberInput } from '../../components/ui'
import { MovementFields } from '../common/Movement'
import type { GameTypeUi, SettingsFormProps } from '../index'

const PRESETS = [2, 8, 16, 32]

/** Mode settings of 极限生存 (mirrors the backend's UhcDefinition). */
export interface UhcSettings {
  players: number
  countdownSeconds: number
  endingSeconds: number
  borderRadius: number
  spawnSpacing: number
  normalPhaseSeconds: number
  pvpGraceSeconds: number
  finalRadius: number
  finalDelaySeconds: number
  finalShrinkSeconds: number
  finalEndRadius: number
  borderDamage: number
  borderDamagePerBlock: number
  naturalRegeneration: boolean
  projectileKnockback: boolean
}

export function readUhcSettings(settings: Record<string, unknown>): UhcSettings {
  const num = (key: string, fallback: number) => Number(settings[key] ?? fallback)
  return {
    players: num('players', 16),
    countdownSeconds: num('countdownSeconds', 15),
    endingSeconds: num('endingSeconds', 10),
    borderRadius: num('borderRadius', 500),
    spawnSpacing: num('spawnSpacing', 100),
    normalPhaseSeconds: num('normalPhaseSeconds', 1200),
    pvpGraceSeconds: num('pvpGraceSeconds', 0),
    finalRadius: num('finalRadius', 150),
    finalDelaySeconds: num('finalDelaySeconds', 30),
    finalShrinkSeconds: num('finalShrinkSeconds', 300),
    finalEndRadius: num('finalEndRadius', 15),
    borderDamage: num('borderDamage', 1),
    borderDamagePerBlock: num('borderDamagePerBlock', 0.2),
    naturalRegeneration: Boolean(settings.naturalRegeneration ?? false),
    projectileKnockback: Boolean(settings.projectileKnockback ?? true),
  }
}

/** "20:00" for 1200 seconds. */
function clock(seconds: number) {
  const s = Math.max(0, Math.round(seconds))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

function UhcSettingsForm({ settings, onChange }: SettingsFormProps) {
  const s = readUhcSettings(settings)
  const set = (patch: Partial<UhcSettings>) => onChange({ ...settings, ...s, ...patch })
  const side = (radius: number) => `${radius * 2} × ${radius * 2} 格`

  return (
    <div className="settings-block">
      <div className="settings-title">极限生存设置</div>
      <div className="preset-row">
        {PRESETS.map((n) => (
          <button type="button" key={n} className={`chip ${s.players === n ? 'chip-active' : ''}`} onClick={() => set({ players: n })}>
            {n} 人
          </button>
        ))}
      </div>
      <div className="grid-3">
        <Field label="每局人数" hint="凑满该人数才开局，各自为战（2-100）">
          <NumberInput value={s.players} min={2} max={100} onChange={(v) => set({ players: Math.round(v) })} />
        </Field>
        <Field label="开局倒计时（秒）" hint="倒计时期间玩家被固定在出生点，不受伤害（0-120）">
          <NumberInput value={s.countdownSeconds} min={0} max={120} onChange={(v) => set({ countdownSeconds: Math.round(v) })} />
        </Field>
        <Field label="结算展示（秒）">
          <NumberInput value={s.endingSeconds} min={1} max={60} onChange={(v) => set({ endingSeconds: Math.round(v) })} />
        </Field>
      </div>

      <div className="settings-title">世界与普通圈</div>
      <div className="grid-3">
        <Field label="边界半径（格）" hint={`边界是正方形，边长 = 2 × 半径：${side(s.borderRadius)}（100-2000）`}>
          <NumberInput value={s.borderRadius} min={100} max={2000} onChange={(v) => set({ borderRadius: Math.round(v) })} />
        </Field>
        <Field label="出生点间距（格）" hint="玩家出生点之间至少相隔多远；地形放不下时开局会自动缩小（0-1000）">
          <NumberInput value={s.spawnSpacing} min={0} max={1000} onChange={(v) => set({ spawnSpacing: Math.round(v) })} />
        </Field>
        <Field label="普通圈时长（秒）" hint={`${clock(s.normalPhaseSeconds)} 内未分出胜负则开启决赛圈（60-7200）`}>
          <NumberInput value={s.normalPhaseSeconds} min={60} max={7200} onChange={(v) => set({ normalPhaseSeconds: Math.round(v) })} />
        </Field>
        <Field label="和平期（秒）" hint="开局后这段时间内玩家之间不能互相伤害，0 = 没有（0-1800）">
          <NumberInput value={s.pvpGraceSeconds} min={0} max={1800} onChange={(v) => set({ pvpGraceSeconds: Math.round(v) })} />
        </Field>
      </div>

      <div className="settings-title">决赛圈</div>
      <div className="grid-3">
        <Field
          label="决赛圈半径（格）"
          hint={s.finalRadius > s.borderRadius ? <span className="text-warn">不能大于边界半径</span> : `存活玩家被传送进地图中央 ${side(s.finalRadius)} 的区域（20-1000）`}
        >
          <NumberInput value={s.finalRadius} min={20} max={1000} onChange={(v) => set({ finalRadius: Math.round(v) })} />
        </Field>
        <Field label="缩圈前等待（秒）" hint="传送后多久开始缩圈（0-600）">
          <NumberInput value={s.finalDelaySeconds} min={0} max={600} onChange={(v) => set({ finalDelaySeconds: Math.round(v) })} />
        </Field>
        <Field label="缩圈时长（秒）" hint="从决赛圈大小缩到最终大小（10-3600）">
          <NumberInput value={s.finalShrinkSeconds} min={10} max={3600} onChange={(v) => set({ finalShrinkSeconds: Math.round(v) })} />
        </Field>
        <Field
          label="最终边界半径（格）"
          hint={s.finalEndRadius > s.finalRadius ? <span className="text-warn">不能大于决赛圈半径</span> : `缩圈结束时 ${side(s.finalEndRadius)}（2-500）`}
        >
          <NumberInput value={s.finalEndRadius} min={2} max={500} onChange={(v) => set({ finalEndRadius: Math.round(v) })} />
        </Field>
        <Field label="边界外伤害（每秒）" hint="站在边界外每秒受到的基础伤害（0-20）">
          <NumberInput value={s.borderDamage} min={0} max={20} step={0.5} onChange={(v) => set({ borderDamage: v })} />
        </Field>
        <Field label="每格额外伤害（每秒）" hint="离边界每远 1 格，每秒再多受这么多伤害（0-5）">
          <NumberInput value={s.borderDamagePerBlock} min={0} max={5} step={0.1} onChange={(v) => set({ borderDamagePerBlock: v })} />
        </Field>
      </div>

      <div className="row wrap" style={{ gap: 24 }}>
        <label className="check">
          <input type="checkbox" checked={s.naturalRegeneration} onChange={(e) => set({ naturalRegeneration: e.target.checked })} />
          饱食度自然回血（关闭 = 经典 UHC：只能靠金苹果、药水等回血）
        </label>
        <label className="check">
          <input type="checkbox" checked={s.projectileKnockback} onChange={(e) => set({ projectileKnockback: e.target.checked })} />
          雪球、鸡蛋、钓鱼竿命中玩家时击退
        </label>
      </div>
      <MovementFields settings={settings} onChange={onChange} />
      <p className="muted small">
        不需要地图：每局都在模组生成的全新世界里进行（彼此相隔很远，不会碰到上一局改过的地形，尽量避开大片海洋）。流程：所有人随机出生在边界内 →
        倒计时结束后开始生存（采集、合成、战斗）→ 普通圈时间到仍有多人存活时开启决赛圈：存活玩家传送到中央，边界缩到决赛圈大小，等待后逐渐收缩到最终大小。
        最后存活的玩家获胜。下方的开局装备默认为空（从零开始），也可以发放少量食物或工具。
      </p>
    </div>
  )
}

export const uhcUi: GameTypeUi = {
  formatLabel: (settings) => `${readUhcSettings(settings).players} 人`,
  SettingsForm: UhcSettingsForm,
  timeLimitHint: '可选的总时限（从开局算起，包含普通圈与决赛圈）：到时仍有多人存活则平局；不限制 = 打到只剩一人',
}
