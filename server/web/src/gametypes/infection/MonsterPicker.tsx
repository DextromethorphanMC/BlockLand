import type { PublishedMonster } from './model'

/**
 * Monsters offered to infected players, in order (first = default). Picks from what the game server published;
 * before any server published its monsters, ids are typed in by hand.
 */
export function MonsterPicker({
  value,
  onChange,
  published,
}: {
  value: string[]
  onChange: (ids: string[]) => void
  published: PublishedMonster[]
}) {
  const move = (index: number, delta: number) => {
    const next = [...value]
    ;[next[index], next[index + delta]] = [next[index + delta], next[index]]
    onChange(next)
  }
  const remove = (index: number) => onChange(value.filter((_, i) => i !== index))
  const byId = new Map(published.map((m) => [m.id, m]))
  const available = published.filter((m) => !value.includes(m.id))

  const rowButtons = (i: number) => (
    <>
      <button type="button" className="icon-btn" disabled={i === 0} onClick={() => move(i, -1)} aria-label="上移" title="上移">
        ↑
      </button>
      <button type="button" className="icon-btn" disabled={i === value.length - 1} onClick={() => move(i, 1)} aria-label="下移" title="下移">
        ↓
      </button>
      <button type="button" className="icon-btn" onClick={() => remove(i)} aria-label="移除" title="移除">
        ×
      </button>
    </>
  )

  if (published.length === 0) {
    return (
      <div className="field">
        <span className="field-label">可选怪物</span>
        <span className="field-hint">
          游戏服务器还没有发布怪物列表（服务器连接后台后自动发布）。可以先手动填写怪物 ID，第一个为默认；留空 = 提供全部怪物。
        </span>
        {value.map((id, i) => (
          <div key={i} className="row">
            <input value={id} placeholder="怪物 ID，例如 zombie" onChange={(e) => onChange(value.map((m, j) => (j === i ? e.target.value.trim().toLowerCase() : m)))} />
            {i === 0 && <span className="muted small">默认</span>}
            {rowButtons(i)}
          </div>
        ))}
        <div className="row">
          <button type="button" className="btn btn-small" onClick={() => onChange([...value, ''])}>
            + 添加怪物
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="field">
      <span className="field-label">可选怪物</span>
      <span className="field-hint">被感染的玩家在面板中按此顺序看到怪物，第一个为默认（选择超时自动使用）。不选择任何怪物 = 提供全部怪物。</span>
      {value.length === 0 && (
        <div className="muted small">
          未选择：提供全部 {published.length} 种怪物，默认为「{published[0].name || published[0].id}」。
        </div>
      )}
      <div className="monster-list">
        {value.map((id, i) => {
          const monster = byId.get(id)
          return (
            <div key={`${id}:${i}`} className="monster-row">
              {monster ? <MonsterInfo monster={monster} /> : <span className="text-warn">{id || '（空）'} · 游戏服务器没有此怪物，模组会忽略</span>}
              <span className="row">
                {i === 0 && <span className="format">默认</span>}
                {rowButtons(i)}
              </span>
            </div>
          )
        })}
      </div>
      {available.length > 0 && (
        <>
          <span className="field-hint">可添加：</span>
          <div className="monster-list">
            {available.map((m) => (
              <div key={m.id} className="monster-row">
                <MonsterInfo monster={m} />
                <button type="button" className="btn btn-small" onClick={() => onChange([...value, m.id])}>
                  + 提供
                </button>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

function MonsterInfo({ monster }: { monster: PublishedMonster }) {
  const facts = [
    monster.health != null && `血量 ${monster.health}`,
    monster.speed != null && `移速 ×${monster.speed}`,
    monster.skill?.name && `技能「${monster.skill.name}」${monster.skill.cooldown != null ? `（冷却 ${monster.skill.cooldown} 秒）` : ''}`,
  ].filter(Boolean)
  return (
    <span className="monster-info">
      <strong>{monster.name || monster.id}</strong> <span className="muted small">{monster.id}</span>
      <span className="small">{facts.join(' · ')}</span>
      {(monster.description || monster.skill?.description) && (
        <span className="muted small">{[monster.description, monster.skill?.description].filter(Boolean).join(' ')}</span>
      )}
    </span>
  )
}
