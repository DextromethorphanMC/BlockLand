/** Movement rules every game type carries (mirrors the backend's MovementSettings). */
export function MovementFields({
  settings,
  onChange,
  note,
}: {
  settings: Record<string, unknown>
  onChange: (settings: Record<string, unknown>) => void
  /** Extra line under the switches (e.g. who of the type's players may use them). */
  note?: string
}) {
  const slide = Boolean(settings.allowSlide ?? false)
  // prone and dive follow the slide in modes saved before they existed
  const prone = Boolean(settings.allowProne ?? slide)
  const dive = Boolean(settings.allowDive ?? slide)
  const vehicles = Boolean(settings.allowVehicles ?? false)
  return (
    <div>
      <label className="check">
        <input type="checkbox" checked={slide} onChange={(e) => onChange({ ...settings, allowSlide: e.target.checked })} />
        允许滑铲
      </label>
      <label className="check">
        <input type="checkbox" checked={prone} onChange={(e) => onChange({ ...settings, allowProne: e.target.checked })} />
        允许匍匐
      </label>
      <label className="check">
        <input type="checkbox" checked={dive} onChange={(e) => onChange({ ...settings, allowDive: e.target.checked })} />
        允许飞扑
      </label>
      <label className="check">
        <input type="checkbox" checked={vehicles} onChange={(e) => onChange({ ...settings, allowVehicles: e.target.checked })} />
        允许载具
      </label>
      <p className="muted small">
        滑铲：冲刺中按下蹲（或「滑铲」键）滑出一段距离，身形和判定框变低。匍匐：按「匍匐」键趴下慢慢爬行，可以开枪瞄准，判定框贴地。
        飞扑：冲刺跳起后在空中按「匍匐」键向前扑出，落地后带着惯性趴下。载具：玩家可以放置和乘坐摩托车、越野车、小游艇、坦克。{note ?? ''}
      </p>
    </div>
  )
}
