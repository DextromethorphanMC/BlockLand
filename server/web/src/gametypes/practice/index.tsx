import { Field, NumberInput } from '../../components/ui'
import { MovementFields } from '../common/Movement'
import type { GameTypeUi, SettingsFormProps } from '../index'

const soloLabel = () => '单人'

/** 枪械瞄准练习 (mirrors the backend's AimPracticeDefinition). */
function AimPracticeSettingsForm({ settings, onChange }: SettingsFormProps) {
  const num = (key: string, fallback: number) => Number(settings[key] ?? fallback)
  const flag = (key: string, fallback: boolean) => Boolean(settings[key] ?? fallback)
  return (
    <div className="settings-block">
      <div className="settings-title">枪械瞄准练习设置</div>
      <div className="grid-3">
        <Field label="假人数量">
          <NumberInput value={num('targetCount', 8)} min={1} max={24} onChange={(v) => onChange({ ...settings, targetCount: v })} />
        </Field>
        <Field label="假人生命值" hint="玩家满血为 20">
          <NumberInput value={num('targetHealth', 20)} min={1} max={200} onChange={(v) => onChange({ ...settings, targetHealth: v })} />
        </Field>
        <Field label="假人复活时间（秒）">
          <NumberInput value={num('respawnSeconds', 1.5)} min={0} max={30} step={0.5} onChange={(v) => onChange({ ...settings, respawnSeconds: v })} />
        </Field>
      </div>
      <div className="row wrap" style={{ gap: 24 }}>
        <label className="check">
          <input type="checkbox" checked={flag('moving', true)} onChange={(e) => onChange({ ...settings, moving: e.target.checked })} />
          假人移动（横移、探头、下蹲）
        </label>
        <label className="check">
          <input type="checkbox" checked={flag('armor', false)} onChange={(e) => onChange({ ...settings, armor: e.target.checked })} />
          假人穿戴护甲
        </label>
      </div>
      <MovementFields settings={settings} onChange={onChange} />
      <p className="muted small">单人模式，无需地图：游戏会自动搭建靶场，发放全部枪械并提供无限弹药。</p>
    </div>
  )
}

/** 搭路练习 (mirrors the backend's BridgePracticeDefinition). */
function BridgePracticeSettingsForm({ settings, onChange }: SettingsFormProps) {
  const num = (key: string, fallback: number) => Number(settings[key] ?? fallback)
  return (
    <div className="settings-block">
      <div className="settings-title">搭路练习设置</div>
      <div className="grid-3">
        <Field label="虚空距离（格）" hint="起点平台到终点平台之间">
          <NumberInput value={num('gapLength', 36)} min={8} max={120} onChange={(v) => onChange({ ...settings, gapLength: v })} />
        </Field>
        <Field label="平台宽度（格）" hint="偶数会自动加一">
          <NumberInput value={num('courseWidth', 9)} min={3} max={31} onChange={(v) => onChange({ ...settings, courseWidth: v })} />
        </Field>
        <Field label="掉落判定深度（格）">
          <NumberInput value={num('voidDepth', 10)} min={4} max={40} onChange={(v) => onChange({ ...settings, voidDepth: v })} />
        </Field>
        <Field label="方块 ID" hint="例如 minecraft:white_wool、minecraft:sandstone">
          <input value={String(settings.blockId ?? 'minecraft:white_wool')} onChange={(e) => onChange({ ...settings, blockId: e.target.value })} />
        </Field>
      </div>
      <label className="check">
        <input type="checkbox" checked={Boolean(settings.timed ?? true)} onChange={(e) => onChange({ ...settings, timed: e.target.checked })} />
        计时并记录最佳用时
      </label>
      <MovementFields settings={settings} onChange={onChange} />
      <p className="muted small">单人模式，无需地图：方块无限，掉下虚空自动回到起点并清空已搭的路。</p>
    </div>
  )
}

export const aimPracticeUi: GameTypeUi = {
  formatLabel: soloLabel,
  SettingsForm: AimPracticeSettingsForm,
  timeLimitHint: '练习模式不需要时间限制，留空即可',
}

export const bridgePracticeUi: GameTypeUi = {
  formatLabel: soloLabel,
  SettingsForm: BridgePracticeSettingsForm,
  timeLimitHint: '练习模式不需要时间限制，留空即可',
}
