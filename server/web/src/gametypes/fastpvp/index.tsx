import { MovementFields } from '../common/Movement'
import { TeamArenaFields, teamFormatLabel } from '../common/TeamArena'
import type { GameTypeUi, SettingsFormProps } from '../index'

function FastPvpSettingsForm({ settings, onChange, maps }: SettingsFormProps) {
  const flag = (key: string) => Boolean(settings[key] ?? false)
  return (
    <div className="settings-block">
      <div className="settings-title">快速PVP设置</div>
      <TeamArenaFields gameType="FASTPVP" settings={settings} onChange={onChange} maps={maps} />
      <div className="row wrap" style={{ gap: 24 }}>
        <label className="check">
          <input type="checkbox" checked={flag('allowBlockBreak')} onChange={(e) => onChange({ ...settings, allowBlockBreak: e.target.checked })} />
          允许破坏方块
        </label>
        <label className="check">
          <input type="checkbox" checked={flag('allowBlockPlace')} onChange={(e) => onChange({ ...settings, allowBlockPlace: e.target.checked })} />
          允许放置方块
        </label>
      </div>
      <MovementFields settings={settings} onChange={onChange} />
      <p className="muted small">开局装备在下方「开局装备」中配置；玩家可以在游戏大厅中调整自己的装备布局。</p>
    </div>
  )
}

export const fastPvpUi: GameTypeUi = {
  formatLabel: teamFormatLabel,
  SettingsForm: FastPvpSettingsForm,
}
