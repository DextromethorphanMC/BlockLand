import { Field, NumberInput } from '../../components/ui'
import { MovementFields } from '../common/Movement'
import type { GameTypeUi, SettingsFormProps } from '../index'

export const WORLD = 'WORLD'

/** Mode settings of 开放世界 (mirrors the backend's WorldDefinition). */
export interface WorldSettings {
  dimension: string
  gameMode: string
  pvp: boolean
  keepInventory: boolean
  maxPlayers: number
}

const GAME_MODES = [
  { value: 'SURVIVAL', label: '生存' },
  { value: 'ADVENTURE', label: '冒险（不能破坏方块）' },
  { value: 'CREATIVE', label: '创造' },
]

export function readWorldSettings(settings: Record<string, unknown>): WorldSettings {
  return {
    dimension: String(settings.dimension ?? 'blockland:world'),
    gameMode: String(settings.gameMode ?? 'SURVIVAL'),
    pvp: Boolean(settings.pvp ?? true),
    keepInventory: Boolean(settings.keepInventory ?? false),
    maxPlayers: Number(settings.maxPlayers ?? 50),
  }
}

function WorldSettingsForm({ settings, onChange }: SettingsFormProps) {
  const s = readWorldSettings(settings)
  const set = (patch: Partial<WorldSettings>) => onChange({ ...settings, ...s, ...patch })

  return (
    <div className="settings-block">
      <div className="settings-title">开放世界设置</div>
      <div className="grid-3">
        <Field label="维度" hint="模组自带 blockland:world（像主世界一样生成）；也可以填 minecraft:overworld 等">
          <input className="mono" value={s.dimension} onChange={(e) => set({ dimension: e.target.value })} />
        </Field>
        <Field label="游戏模式">
          <select value={s.gameMode} onChange={(e) => set({ gameMode: e.target.value })}>
            {GAME_MODES.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="最大人数" hint="同时在这个世界里的玩家上限（1-200）">
          <NumberInput value={s.maxPlayers} min={1} max={200} onChange={(v) => set({ maxPlayers: Math.round(v) })} />
        </Field>
      </div>
      <div className="row wrap" style={{ gap: 24 }}>
        <label className="check">
          <input type="checkbox" checked={s.pvp} onChange={(e) => set({ pvp: e.target.checked })} />
          允许玩家互相攻击（PVP）
        </label>
        <label className="check">
          <input type="checkbox" checked={s.keepInventory} onChange={(e) => set({ keepInventory: e.target.checked })} />
          死亡不掉落
        </label>
      </div>
      <MovementFields settings={settings} onChange={onChange} />
      <p className="muted small">
        不需要匹配：点「开始」立即进入正在运行的世界（没有时会自动创建）。玩家的背包、位置、血量与经验由游戏服务器保存，下次进入时恢复；
        下方的开局装备只在玩家第一次进入时发放。死亡后在世界出生点复活。
      </p>
    </div>
  )
}

export const worldUi: GameTypeUi = {
  formatLabel: (settings) => `最多 ${readWorldSettings(settings).maxPlayers} 人`,
  SettingsForm: WorldSettingsForm,
  timeLimitHint: '开放世界不限时，此项不生效',
}
