/** Movement rules every game type carries (mirrors the backend's MovementSettings). */
export function MovementFields({
  settings,
  onChange,
  note,
}: {
  settings: Record<string, unknown>
  onChange: (settings: Record<string, unknown>) => void
  /** Extra line under the switch (e.g. who of the type's players may slide). */
  note?: string
}) {
  return (
    <div>
      <label className="check">
        <input
          type="checkbox"
          checked={Boolean(settings.allowSlide ?? false)}
          onChange={(e) => onChange({ ...settings, allowSlide: e.target.checked })}
        />
        允许滑铲
      </label>
      <p className="muted small">冲刺中按下蹲（或「滑铲」键）滑出一段距离，滑铲时身形和判定框变低。{note ?? ''}</p>
    </div>
  )
}
