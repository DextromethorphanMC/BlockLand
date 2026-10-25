import type {
  AntiCheatCheckField,
  AntiCheatCheckInfo,
  AntiCheatCheckSettings,
  AntiCheatConfig,
  AntiCheatPreset,
} from '../types'

/** Check categories in display order (the mod's checks.json `category`). */
export const CHECK_CATEGORIES: { id: string; label: string }[] = [
  { id: 'movement', label: '移动' },
  { id: 'combat', label: '战斗' },
  { id: 'packet', label: '数据包' },
  { id: 'world', label: '世界' },
]

export function categoryName(id: string) {
  return CHECK_CATEGORIES.find((c) => c.id === id)?.label ?? id
}

/** A check's display name from the registry, its id when the game server has not published it. */
export function checkName(id: string, registry: AntiCheatCheckInfo[] | undefined) {
  return registry?.find((c) => c.id === id)?.name ?? id
}

/** Used when neither the registry nor any layer says anything (keeps the table usable before a server started). */
const FALLBACK: Required<AntiCheatCheckSettings> = {
  enabled: true,
  tolerance: 1,
  setbackVl: 0,
  kickVl: 0,
  decay: 0.5,
  weight: 0.5,
  punish: false,
}

/** The effective settings of a check: the registry's defaults, then each layer (preset, mode) on top. */
export function resolveCheck(
  id: string,
  registry: AntiCheatCheckInfo[] | undefined,
  ...layers: (Record<string, AntiCheatCheckSettings> | undefined)[]
): Required<AntiCheatCheckSettings> {
  const base = { ...FALLBACK, ...(registry?.find((c) => c.id === id)?.defaults ?? {}) }
  for (const layer of layers) {
    const own = layer?.[id]
    if (!own) continue
    for (const [key, value] of Object.entries(own)) {
      if (value !== undefined && value !== null) (base as Record<string, unknown>)[key] = value
    }
  }
  return base
}

/** Label of a preset in pickers: its name and id. */
export function presetLabel(id: string, preset: AntiCheatPreset | undefined) {
  return preset?.name ? `${preset.name}（${id}）` : id
}

/**
 * Default preset of a game type when a new mode does not name one yet. The backend decides it on save
 * (GameTypeDefinition.antiCheatPreset); this mirrors the spec's defaults for display only.
 */
export function typeDefaultPreset(typeId: string, defaultSettings: Record<string, unknown> | undefined) {
  const fromType = (defaultSettings?.antiCheat as { preset?: unknown } | undefined)?.preset
  if (typeof fromType === 'string' && fromType) return fromType
  if (typeId === 'BOMB') return 'strict'
  if (typeId === 'WORLD' || typeId === 'AIM_PRACTICE' || typeId === 'BRIDGE_PRACTICE') return 'relaxed'
  return 'standard'
}

/** The preset a mode resolves to: its own, else the default preset, else nothing (the mod falls back to standard values). */
export function presetOf(config: AntiCheatConfig | undefined, id: string | undefined) {
  if (!config) return undefined
  return (id && config.presets[id]) || config.presets[config.defaultPreset] || config.presets.standard
}

type NumberField = Exclude<AntiCheatCheckField, 'enabled' | 'punish'>

const NUMBER_COLUMNS: { field: NumberField; label: string; title: string; min: number; max: number; step: number }[] = [
  { field: 'tolerance', label: '宽限', title: '宽限倍率 0.1–5，乘在该检测所有宽限量上（> 1 更宽松）', min: 0.1, max: 5, step: 0.05 },
  { field: 'setbackVl', label: '回退 VL', title: 'VL 达到后撤销违规动作（回退位置 / 取消攻击）；0 = 每次违规都撤销或从不回退（视检测而定）', min: 0, max: 1000, step: 1 },
  { field: 'kickVl', label: '踢出 VL', title: 'VL 达到后踢出；0 = 从不', min: 0, max: 10000, step: 1 },
  { field: 'decay', label: '衰减/秒', title: '不再违规时每秒减少的 VL', min: 0, max: 100, step: 0.05 },
  { field: 'weight', label: '权重', title: '计入置信度的权重 0–1', min: 0, max: 1, step: 0.05 },
]

/**
 * Per-check overrides: every field blank = inherited from {@code base} (shown as the placeholder). Used by the preset
 * editor (base = the registry's defaults) and the mode field (base = the registry's defaults with the preset on top).
 */
export function CheckOverridesTable({
  registry,
  value,
  onChange,
  base,
  inheritLabel,
}: {
  registry: AntiCheatCheckInfo[] | undefined
  value: Record<string, AntiCheatCheckSettings>
  onChange: (next: Record<string, AntiCheatCheckSettings>) => void
  /** What a blank field inherits. */
  base: (id: string) => Required<AntiCheatCheckSettings>
  /** "默认" or "预设" — whose value a blank field shows. */
  inheritLabel: string
}) {
  const known = registry ?? []
  // overrides of checks the server no longer (or not yet) publishes stay visible so they can be cleared
  const unknown = Object.keys(value)
    .filter((id) => !known.some((c) => c.id === id))
    .map<AntiCheatCheckInfo>((id) => ({
      id,
      category: id.includes('.') ? id.slice(0, id.indexOf('.')) : 'other',
      name: id,
      description: '游戏服务器未上报该检测',
      latencySensitive: false,
      signal: false,
      defaults: { ...FALLBACK },
    }))
  const all = [...known, ...unknown]
  const categories = [
    ...CHECK_CATEGORIES.map((c) => c.id),
    ...new Set(all.map((c) => c.category).filter((c) => !CHECK_CATEGORIES.some((k) => k.id === c))),
  ]

  const setField = (id: string, field: AntiCheatCheckField, v: number | boolean | undefined) => {
    const own: AntiCheatCheckSettings = { ...(value[id] ?? {}) }
    if (v === undefined) delete own[field]
    else (own as Record<string, unknown>)[field] = v
    const next = { ...value }
    if (Object.keys(own).length === 0) delete next[id]
    else next[id] = own
    onChange(next)
  }

  const boolSelect = (id: string, field: 'enabled' | 'punish', inherited: boolean) => {
    const own = value[id]?.[field]
    return (
      <select
        value={own === undefined ? '' : own ? 'yes' : 'no'}
        onChange={(e) => setField(id, field, e.target.value === '' ? undefined : e.target.value === 'yes')}
        aria-label={field === 'enabled' ? '启用' : '可封禁'}
      >
        <option value="">
          {inheritLabel}（{inherited ? '是' : '否'}）
        </option>
        <option value="yes">是</option>
        <option value="no">否</option>
      </select>
    )
  }

  if (all.length === 0) {
    return <p className="muted small">游戏服务器尚未上报检测目录（启动一次安装了 BlockLand 的服务器后出现），暂时无法按检测覆盖。</p>
  }

  return (
    <div className="table-scroll">
      <table className="table table-compact ac-checks">
        <thead>
          <tr>
            <th>检测</th>
            <th title="是否检测">启用</th>
            {NUMBER_COLUMNS.map((c) => (
              <th key={c.field} title={c.title}>
                {c.label}
              </th>
            ))}
            <th title="是否可以作为自动封禁的依据（否则只进人工复核）">可封禁</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {categories.map((category) => {
            const rows = all.filter((c) => c.category === category)
            if (rows.length === 0) return null
            return [
              <tr key={`cat-${category}`} className="ac-category">
                <td colSpan={NUMBER_COLUMNS.length + 4}>{categoryName(category)}</td>
              </tr>,
              ...rows.map((check) => {
                const inherited = base(check.id)
                const own = value[check.id] ?? {}
                return (
                  <tr key={check.id} className={Object.keys(own).length ? 'row-selected' : ''}>
                    <td title={check.description}>
                      <strong>{check.name}</strong>
                      {check.signal && <span className="badge ac-signal">信号</span>}
                      <div className="small muted">
                        <code>{check.id}</code>
                      </div>
                    </td>
                    <td>{boolSelect(check.id, 'enabled', inherited.enabled)}</td>
                    {NUMBER_COLUMNS.map((c) => (
                      <td key={c.field}>
                        <input
                          type="number"
                          className="num"
                          min={c.min}
                          max={c.max}
                          step={c.step}
                          value={own[c.field] ?? ''}
                          placeholder={String(inherited[c.field])}
                          onChange={(e) => setField(check.id, c.field, e.target.value === '' ? undefined : Number(e.target.value))}
                          aria-label={c.label}
                        />
                      </td>
                    ))}
                    <td>{boolSelect(check.id, 'punish', inherited.punish)}</td>
                    <td>
                      {Object.keys(own).length > 0 && (
                        <button
                          type="button"
                          className="icon-btn"
                          title="清除该检测的覆盖"
                          onClick={() => {
                            const next = { ...value }
                            delete next[check.id]
                            onChange(next)
                          }}
                        >
                          ×
                        </button>
                      )}
                    </td>
                  </tr>
                )
              }),
            ]
          })}
        </tbody>
      </table>
    </div>
  )
}
