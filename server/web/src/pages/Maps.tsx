import { useRef, useState, type FormEvent, type MouseEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, mediaUrl } from '../api'
import { confirmAction, Empty, ErrorBox, Field, Modal, PageHeader, useLoad, useToast } from '../components/ui'
import type { GameMap } from '../types'

export function Maps() {
  const toast = useToast()
  const maps = useLoad(api.maps)
  const types = useLoad(api.gameTypes)
  const [uploading, setUploading] = useState(false)

  const remove = async (map: GameMap) => {
    if (!confirmAction(`确定删除地图「${map.name}」？使用该地图的模式会自动移除它。`)) return
    try {
      await api.deleteMap(map.id)
      toast.ok('已删除')
      maps.reload()
    } catch (e) {
      toast.error(e)
    }
  }

  return (
    <>
      <PageHeader
        title="地图管理"
        subtitle="导入原版结构文件 (.nbt) 或 WorldEdit 导出的 .schem。导入时会自动识别出生点标记方块；各游戏类型会读取自己需要的内容（如空岛战争识别箱子坐标）。封面会显示在游戏大厅的地图选择中。"
        actions={
          <button className="btn btn-primary" onClick={() => setUploading(true)}>
            ⬆ 导入地图
          </button>
        }
      />
      <ErrorBox error={maps.error} />
      {maps.data?.length === 0 && <Empty>还没有地图。</Empty>}
      <table className="table">
        <thead>
          <tr>
            <th>封面</th>
            <th>名称</th>
            <th>类型</th>
            <th>尺寸 (X×Y×Z)</th>
            <th>队伍出生点</th>
            <th>文件</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {maps.data?.map((m) => (
            <tr key={m.id}>
              <td className="map-cover-cell">
                <MapCover map={m} onChanged={maps.reload} />
              </td>
              <td>
                <Link to={`/maps/${m.id}`}>
                  <strong>{m.name}</strong>
                </Link>
                <div className="small muted">
                  <code>{m.id}</code>
                </div>
              </td>
              <td>{types.data?.find((t) => t.id === m.gameType)?.displayName ?? m.gameType}</td>
              <td>
                {m.size.x}×{m.size.y}×{m.size.z}
              </td>
              <td>{m.teamSpawns.length}</td>
              <td className="small muted">
                {m.fileName} · {(m.fileSize / 1024).toFixed(0)} KB
              </td>
              <td className="row end">
                <button className="btn btn-ghost" onClick={() => remove(m)}>
                  删除
                </button>
                <Link className="btn" to={`/maps/${m.id}`}>
                  配置
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {uploading && <UploadDialog types={types.data ?? []} onClose={() => setUploading(false)} />}
    </>
  )
}

/** Cover thumbnail: click to upload (scaled to 512×288 by the backend), × to remove. */
function MapCover({ map, onChanged }: { map: GameMap; onChanged: () => void }) {
  const toast = useToast()
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const url = mediaUrl('map', map.id, map.coverVersion)

  const upload = async (file: File | null) => {
    if (!file) return
    setBusy(true)
    try {
      await api.uploadMapCover(map.id, file)
      toast.ok(`已更新「${map.name}」的封面`)
      onChanged()
    } catch (e) {
      toast.error(e)
    } finally {
      setBusy(false)
      if (input.current) input.current.value = ''
    }
  }

  const remove = async (e: MouseEvent) => {
    e.stopPropagation()
    if (!confirmAction(`移除地图「${map.name}」的封面？`)) return
    try {
      await api.removeMapCover(map.id)
      toast.ok('已移除封面')
      onChanged()
    } catch (err) {
      toast.error(err)
    }
  }

  return (
    <div
      className="map-cover"
      role="button"
      tabIndex={0}
      title="点击上传封面（建议 16:9，如 1024×576）"
      onClick={() => !busy && input.current?.click()}
      onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && !busy && input.current?.click()}
    >
      {busy ? <span>上传中…</span> : url ? <img src={url} alt="" /> : <span>+ 封面</span>}
      {url && !busy && (
        <button type="button" className="map-cover-remove" aria-label="移除封面" title="移除封面" onClick={remove}>
          ×
        </button>
      )}
      <input ref={input} type="file" accept="image/*" hidden onChange={(e) => upload(e.target.files?.[0] ?? null)} />
    </div>
  )
}

function UploadDialog({ types, onClose }: { types: { id: string; displayName: string }[]; onClose: () => void }) {
  const toast = useToast()
  const navigate = useNavigate()
  const [file, setFile] = useState<File | null>(null)
  const [id, setId] = useState('')
  const [name, setName] = useState('')
  const [gameType, setGameType] = useState(types[0]?.id ?? 'SKYWARS')
  const [marker, setMarker] = useState('')
  const [busy, setBusy] = useState(false)

  const pick = (f: File | null) => {
    setFile(f)
    if (f) {
      const base = f.name.replace(/\.(nbt|schem)$/i, '')
      if (!name) setName(base)
      if (!id) setId(base.toLowerCase().replace(/[^a-z0-9_-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 48))
    }
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    form.append('id', id)
    form.append('name', name)
    form.append('gameType', gameType)
    if (marker.trim()) form.append('spawnMarkerBlock', marker.trim())
    setBusy(true)
    try {
      const map = await api.uploadMap(form)
      toast.ok(`已导入：识别到 ${map.teamSpawns.length} 组出生点`)
      navigate(`/maps/${map.id}`)
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title="导入地图" onClose={onClose}>
      <form className="form" onSubmit={submit}>
        <label className={`dropzone ${file ? 'has-file' : ''}`}>
          <input type="file" accept=".nbt,.schem" onChange={(e) => pick(e.target.files?.[0] ?? null)} />
          {file ? (
            <span>
              {file.name} <small className="muted">({(file.size / 1024).toFixed(0)} KB)</small>
            </span>
          ) : (
            <span>点击选择 .nbt / .schem 地图文件</span>
          )}
        </label>
        <div className="grid-2">
          <Field label="地图名称">
            <input value={name} onChange={(e) => setName(e.target.value)} required />
          </Field>
          <Field label="地图 ID">
            <input value={id} onChange={(e) => setId(e.target.value.toLowerCase())} required />
          </Field>
          <Field label="游戏类型">
            <select value={gameType} onChange={(e) => setGameType(e.target.value)}>
              {types.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.displayName}
                </option>
              ))}
            </select>
          </Field>
          <Field label="出生点标记方块（可选）" hint="例如 minecraft:gold_block。相邻的标记会归为同一个出生岛，粘贴地图时标记方块会被移除。">
            <input value={marker} onChange={(e) => setMarker(e.target.value)} placeholder="minecraft:gold_block" />
          </Field>
        </div>
        <div className="row end">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary" disabled={busy || !file}>
            {busy ? '解析中…' : '导入'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
