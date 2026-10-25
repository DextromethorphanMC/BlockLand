import { Link } from 'react-router-dom'
import { api } from '../../api'
import { CheckOverridesTable, presetLabel, presetOf, resolveCheck, typeDefaultPreset } from '../../components/AntiCheatChecks'
import { Field, useLoad } from '../../components/ui'
import type { AntiCheatModeSettings } from '../../types'

/**
 * 反作弊 of one mode (`settings.antiCheat`, every game type has it; mirrors the backend's GenericModeSettings): on/off,
 * the preset, the tolerance scale and per-check overrides. Blank fields inherit the preset; the presets themselves are
 * edited on the 反作弊 page.
 */
export function AntiCheatField({
  settings,
  onChange,
  typeId,
  typeDefaults,
}: {
  settings: Record<string, unknown>
  onChange: (settings: Record<string, unknown>) => void
  typeId: string
  typeDefaults: Record<string, unknown> | undefined
}) {
  const config = useLoad(api.antiCheatConfig)
  const registry = useLoad(api.antiCheatRegistry)
  const raw = settings.antiCheat
  const ac: AntiCheatModeSettings = raw && typeof raw === 'object' ? (raw as AntiCheatModeSettings) : {}
  const enabled = ac.enabled !== false
  const fallback = typeDefaultPreset(typeId, typeDefaults)
  const presetId = ac.preset ?? ''
  const preset = presetOf(config.data, presetId || fallback)
  const set = (patch: Partial<AntiCheatModeSettings>) => {
    const next: AntiCheatModeSettings = { ...ac, ...patch }
    if (!next.preset) delete next.preset
    if (next.checks && Object.keys(next.checks).length === 0) delete next.checks
    onChange({ ...settings, antiCheat: next })
  }
  const presets = Object.entries(config.data?.presets ?? {})
  const missing = presetId && config.data && !config.data.presets[presetId]
  const overrides = Object.keys(ac.checks ?? {}).length

  return (
    // a div, not a <label>: a click anywhere in the table would otherwise toggle the first checkbox
    <div className="field">
      <span className="field-label">反作弊</span>
      <div className="settings-block">
        <div className="grid-3">
          <label className="check">
            <input type="checkbox" checked={enabled} onChange={(e) => set({ enabled: e.target.checked })} />
            在该模式中启用反作弊
          </label>
          <Field label="预设">
            <select value={presetId} disabled={!enabled} onChange={(e) => set({ preset: e.target.value || undefined })}>
              <option value="">游戏类型默认（{presetLabel(fallback, config.data?.presets[fallback])}）</option>
              {presets.map(([id, p]) => (
                <option key={id} value={id}>
                  {presetLabel(id, p)}
                </option>
              ))}
              {missing && <option value={presetId}>{presetId}（不存在，按默认预设处理）</option>}
            </select>
          </Field>
          <Field label="宽限倍率" hint="留空 = 预设的值；0.1–5，> 1 更宽松">
            <input
              type="number"
              min={0.1}
              max={5}
              step={0.05}
              disabled={!enabled}
              value={ac.toleranceScale ?? ''}
              placeholder={preset ? String(preset.toleranceScale) : '1'}
              onChange={(e) => set({ toleranceScale: e.target.value === '' ? null : Number(e.target.value) })}
            />
          </Field>
        </div>
        {preset?.description && <p className="muted small">{preset.description}</p>}
        {!enabled && <p className="text-warn small">该模式不做任何反作弊检测（玩家状态仍在跟踪，重新启用后立即生效）。</p>}
        {enabled && (
          <details className="ac-details" open={overrides > 0}>
            <summary>
              按检测覆盖{overrides > 0 ? `（已覆盖 ${overrides} 项）` : '（全部跟随预设）'}
            </summary>
            <CheckOverridesTable
              registry={registry.data}
              value={ac.checks ?? {}}
              onChange={(checks) => set({ checks })}
              base={(id) => resolveCheck(id, registry.data, preset?.checks)}
              inheritLabel="预设"
            />
          </details>
        )}
      </div>
      <span className="field-hint">
        服务端检测移动、数据包与战斗作弊。预设决定宽限、延迟补偿、处罚阈值与是否自动封禁，在{' '}
        <Link to="/anticheat?tab=settings">反作弊 · 设置</Link> 中编辑；这里留空的项都跟随预设。
      </span>
    </div>
  )
}
