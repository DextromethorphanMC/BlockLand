import { Field, NumberInput, useLoad } from '../../components/ui'
import type { GameMap } from '../../types'
import { TeamArenaFields, teamFormatLabel } from '../common/TeamArena'
import type { GameTypeUi, MapOverlay, SettingsFormProps } from '../index'
import { LOOT_COLORS, lootColor, readMapSettings, skyWarsApi } from './api'
import { LootTablesPage } from './LootTablesPage'
import { SkyWarsMapPanel } from './SkyWarsMapPanel'

function SkyWarsSettingsForm({ settings, onChange, maps }: SettingsFormProps) {
  const refill = Number(settings.chestRefillSeconds ?? 0)
  return (
    <div className="settings-block">
      <div className="settings-title">空岛战争设置</div>
      <TeamArenaFields gameType="SKYWARS" settings={settings} onChange={onChange} maps={maps} />
      <div className="grid-3">
        <Field label="箱子刷新间隔（秒）" hint="0 = 不刷新">
          <NumberInput value={refill} min={0} max={3600} onChange={(v) => onChange({ ...settings, chestRefillSeconds: v })} />
        </Field>
      </div>
    </div>
  )
}

function useSkyWarsOverlay(map: GameMap): MapOverlay {
  const loot = useLoad(skyWarsApi.lootTables)
  const tables = loot.data ?? []
  const s = readMapSettings(map.settings)
  return {
    markers: s.chests.map((c, i) => ({ id: `chest:${i}`, x: c.x, z: c.z, color: lootColor(tables, c.lootTableId, s.defaultLootTableId) })),
    legend: tables.map((t, i) => ({ label: t.name, color: LOOT_COLORS[i % LOOT_COLORS.length] })),
  }
}

export const skyWarsUi: GameTypeUi = {
  formatLabel: teamFormatLabel,
  SettingsForm: SkyWarsSettingsForm,
  MapPanel: SkyWarsMapPanel,
  useMapOverlay: useSkyWarsOverlay,
  pages: [{ path: '/skywars/loot', label: '空岛 · 物资表', element: <LootTablesPage /> }],
}
