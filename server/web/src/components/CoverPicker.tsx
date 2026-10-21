import { useEffect, useRef, useState } from 'react'

/**
 * 16:9 cover picker. The chosen file is only held locally; the caller uploads it after saving
 * (new entities need an id first).
 */
export function CoverPicker({ current, onPick, label = '封面' }: { current: string | null; onPick: (file: File | null) => void; label?: string }) {
  const input = useRef<HTMLInputElement>(null)
  const [preview, setPreview] = useState<string | null>(current)

  useEffect(() => setPreview(current), [current])

  return (
    <div className="field">
      <span className="field-label">{label}</span>
      <button type="button" className="cover-pick" onClick={() => input.current?.click()}>
        {preview ? <img src={preview} alt="" /> : <span className="muted small">点击上传封面（建议 16:9，如 1024×576）</span>}
      </button>
      <input
        ref={input}
        type="file"
        accept="image/*"
        hidden
        onChange={(e) => {
          const file = e.target.files?.[0] ?? null
          onPick(file)
          if (file) setPreview(URL.createObjectURL(file))
        }}
      />
      <span className="field-hint">保存后上传；会被缩放到 512×288 以内并同步到游戏内大厅。</span>
    </div>
  )
}
