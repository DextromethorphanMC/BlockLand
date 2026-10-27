import { Field, NumberInput } from '../../components/ui'
import { LootTableField, readLootRows } from '../common/LootTableField'
import { MapPoolField } from '../common/MapPoolField'
import { MovementFields } from '../common/Movement'
import type { GameTypeUi, SettingsFormProps } from '../index'

const LUCKY_PILLARS = 'LUCKY_PILLARS'
const PRESETS = [2, 4, 8, 16]

/** 幸运高柱 (mirrors the backend's LuckyPillarsDefinition). */
function LuckyPillarsSettingsForm({ settings, onChange, maps }: SettingsFormProps) {
  const num = (key: string, fallback: number) => Number(settings[key] ?? fallback)
  const players = num('players', 16)
  const mapIds = Array.isArray(settings.mapIds) ? (settings.mapIds as string[]) : []
  const set = (patch: Record<string, unknown>) => onChange({ ...settings, ...patch })

  return (
    <div className="settings-block">
      <div className="settings-title">幸运高柱设置</div>
      <div className="preset-row">
        {PRESETS.map((n) => (
          <button type="button" key={n} className={`chip ${players === n ? 'chip-active' : ''}`} onClick={() => set({ players: n })}>
            {n} 人
          </button>
        ))}
      </div>
      <div className="grid-3">
        <Field label="每局人数" hint="凑满该人数才开局，各自为战，每人一根高柱（2-64）">
          <NumberInput value={players} min={2} max={64} onChange={(v) => set({ players: Math.round(v) })} />
        </Field>
        <Field label="开局倒计时（秒）" hint="倒计时期间玩家被玻璃罩在柱顶">
          <NumberInput value={num('countdownSeconds', 10)} min={0} max={120} onChange={(v) => set({ countdownSeconds: Math.round(v) })} />
        </Field>
        <Field label="结算展示（秒）">
          <NumberInput value={num('endingSeconds', 8)} min={1} max={60} onChange={(v) => set({ endingSeconds: Math.round(v) })} />
        </Field>
        <Field label="发放间隔（秒）" hint="开打后第 1 秒发第一次，之后每隔这么久发一次（1-300）">
          <NumberInput value={num('itemIntervalSeconds', 8)} min={1} max={300} onChange={(v) => set({ itemIntervalSeconds: Math.round(v) })} />
        </Field>
        <Field label="每次发放数量" hint="每名存活玩家每次获得几件（1-8）">
          <NumberInput value={num('itemsPerDrop', 1)} min={1} max={8} onChange={(v) => set({ itemsPerDrop: Math.round(v) })} />
        </Field>
      </div>

      <LootTableField
        title="物品表"
        emptyText="物品表为空：玩家不会获得任何物品。"
        loot={readLootRows(settings.items)}
        onChange={(items) => set({ items })}
        hint="每次发放时按权重为每名存活玩家抽取物品，数量在最少与最多之间随机；背包放不下的掉在脚下。方块是搭路的关键，建议保持较高权重。"
      />

      <MapPoolField
        gameType={LUCKY_PILLARS}
        maps={maps}
        mapIds={mapIds}
        onChange={(ids) => set({ mapIds: ids })}
        hint="不勾选任何地图 = 使用该类型的全部地图。地图的每个队伍出生点就是一根高柱（放在柱顶），数量需不少于每局人数。"
        describe={(map) => {
          const pillars = map.teamSpawns.filter((t) => t.points.length > 0).length
          return pillars >= players ? { ok: true, text: `${pillars} 根高柱` } : { ok: false, text: `只有 ${pillars} 根高柱，需要 ${players} 根` }
        }}
      />
      <label className="check">
        <input
          type="checkbox"
          checked={Boolean(settings.projectileKnockback ?? true)}
          onChange={(e) => set({ projectileKnockback: e.target.checked })}
        />
        雪球、鸡蛋、钓鱼竿命中玩家时击退（不扣血，被打下高柱算投掷者的击杀）
      </label>
      <MovementFields settings={settings} onChange={onChange} />
      <p className="muted small">玩家开局两手空空（无需开局装备）；示例地图的高柱是基岩，无法破坏，玩家只能在地图范围内搭建。</p>
    </div>
  )
}

export const luckyPillarsUi: GameTypeUi = {
  formatLabel: (settings) => `${Number(settings.players ?? 16)} 人`,
  SettingsForm: LuckyPillarsSettingsForm,
  timeLimitHint: '可选的总时限：到时仍有多人存活则平局；不限制 = 打到只剩一人',
}
