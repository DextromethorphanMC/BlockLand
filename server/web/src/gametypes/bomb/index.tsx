import { Field, NumberInput } from '../../components/ui'
import { MapPoolField } from '../common/MapPoolField'
import { MovementFields } from '../common/Movement'
import type { GameTypeUi, SettingsFormProps } from '../index'
import { BombMapPanel, useBombOverlay } from './BombMapPanel'
import { BOMB, readSettings, readSites, spawnGroups, type BombSettings } from './model'

const PRESETS = [1, 2, 3, 4, 5]

function BombSettingsForm({ settings, onChange, maps }: SettingsFormProps) {
  const s = readSettings(settings)
  const set = (patch: Partial<BombSettings>) => onChange({ ...settings, ...s, ...patch, teamCount: 2 })

  return (
    <div className="settings-block">
      <div className="settings-title">枪战爆破设置</div>
      <div className="preset-row">
        {PRESETS.map((n) => (
          <button type="button" key={n} className={`chip ${s.teamSize === n ? 'chip-active' : ''}`} onClick={() => set({ teamSize: n })}>
            {n}v{n}
          </button>
        ))}
      </div>
      <div className="grid-3">
        <Field label="每队人数" hint="进攻方与防守方各一队">
          <NumberInput value={s.teamSize} min={1} max={64} onChange={(v) => set({ teamSize: v })} />
        </Field>
        <Field label="每局人数">
          <input value={`${s.teamSize * 2} 人`} disabled />
        </Field>
        <Field label="开局倒计时（秒）">
          <NumberInput value={s.countdownSeconds} min={0} max={120} onChange={(v) => set({ countdownSeconds: v })} />
        </Field>
        <Field label="结算展示（秒）">
          <NumberInput value={s.endingSeconds} min={1} max={60} onChange={(v) => set({ endingSeconds: v })} />
        </Field>
        <Field label="安放时间（秒）" hint="在包点内按住左键多久完成安放（1-15）">
          <NumberInput value={s.plantSeconds} min={1} max={15} step={0.1} onChange={(v) => set({ plantSeconds: v })} />
        </Field>
        <Field label="拆除时间（秒）" hint="防守方按住拆弹键多久完成拆除（1-30）">
          <NumberInput value={s.defuseSeconds} min={1} max={30} step={0.5} onChange={(v) => set({ defuseSeconds: v })} />
        </Field>
        <Field label="爆炸倒计时（秒）" hint="安放后多久爆炸，由服务器计时（10-120）">
          <NumberInput value={s.fuseSeconds} min={10} max={120} onChange={(v) => set({ fuseSeconds: Math.round(v) })} />
        </Field>
      </div>
      <p className="muted small">
        赛制：一局定胜负。进攻方中随机一人携带炸药包（阵亡或丢弃后掉落在原地，只有进攻方能捡起）。炸弹爆炸 → 进攻方获胜；
        防守方拆除炸弹、安放时间耗尽或进攻方在安放前全部阵亡 → 防守方获胜。安放后进攻方全部阵亡时防守方仍需拆除炸弹。爆炸不会破坏方块。
      </p>

      <MapPoolField
        gameType={BOMB}
        maps={maps}
        mapIds={s.mapIds}
        onChange={(mapIds) => set({ mapIds })}
        hint="不勾选任何地图 = 使用该类型的全部地图。包点在「地图管理」中设置；队伍出生点第 1 组为进攻方、第 2 组为防守方。"
        describe={(map) => {
          const sites = readSites(map.settings).length
          const groups = spawnGroups(map)
          return { ok: sites > 0 && groups >= 2, text: `${sites} 个包点 · ${groups} 个出生组` }
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
      <p className="muted small">开局装备在下方「开局装备」中配置（炸药包由游戏另行发放，不要放进装备里）；玩家可以在游戏大厅中调整自己的装备布局，包括炸药包的位置。</p>
    </div>
  )
}

export const bombUi: GameTypeUi = {
  formatLabel: (settings) => {
    const s = readSettings(settings)
    return `${s.teamSize}v${s.teamSize}`
  },
  SettingsForm: BombSettingsForm,
  MapPanel: BombMapPanel,
  useMapOverlay: useBombOverlay,
  timeLimitHint: '进攻方安放炸弹的时间：时间耗尽仍未安放则防守方获胜；炸弹安放后改由爆炸倒计时决定。不限制 = 没有安放时限',
}
