import { useState, type FormEvent } from 'react'
import { api, type AnnouncementInput } from '../api'
import { confirmAction, Empty, ErrorBox, Field, Modal, PageHeader, useLoad, useToast } from '../components/ui'
import type { Announcement } from '../types'
import { formatTime } from './Users'

const MAX_TITLE = 60
const MAX_CONTENT = 2000
/** Longer contents are collapsed in the list. */
const PREVIEW_LINES = 4
const PREVIEW_CHARS = 220

/** Characters as the backend counts them (code points, so an emoji is one). */
const length = (text: string) => [...text].length

/** Pinned first, then newest first. */
function ordered(list: Announcement[]) {
  return [...list].sort((a, b) => Number(b.pinned) - Number(a.pinned) || Date.parse(b.publishedAt) - Date.parse(a.publishedAt))
}

export function Announcements() {
  const toast = useToast()
  const announcements = useLoad(api.announcements)
  const [editing, setEditing] = useState<Announcement | 'new' | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  const remove = async (a: Announcement) => {
    if (!confirmAction(`确定删除公告「${a.title}」？玩家将不再看到它。`)) return
    try {
      await api.deleteAnnouncement(a.id)
      toast.ok('已删除')
      announcements.reload()
    } catch (e) {
      toast.error(e)
    }
  }

  const toggle = (id: string) =>
    setExpanded((set) => {
      const next = new Set(set)
      if (!next.delete(id)) next.add(id)
      return next
    })

  return (
    <>
      <PageHeader
        title="公告"
        subtitle="玩家在游戏大厅的「公告」页查看全部公告；未读的公告会提醒在大厅右上角（最多同时两条，按发布时间从旧到新，玩家点「已阅」后显示下一条）。发布、修改和删除会在游戏服务器下次同步配置时生效。"
        actions={
          <button className="btn btn-primary" onClick={() => setEditing('new')}>
            + 发布公告
          </button>
        }
      />
      <ErrorBox error={announcements.error} />
      {announcements.data?.length === 0 && <Empty>还没有公告。</Empty>}
      <div className="announcement-list">
        {announcements.data &&
          ordered(announcements.data).map((a) => {
            const long = a.content.split('\n').length > PREVIEW_LINES || length(a.content) > PREVIEW_CHARS
            const open = expanded.has(a.id)
            return (
              <article key={a.id} className={`card announcement-card ${a.pinned ? 'pinned' : ''}`}>
                <div className="row between">
                  <div className="row wrap">
                    {a.pinned && <span className="badge badge-limited">置顶</span>}
                    <h3>{a.title}</h3>
                  </div>
                  <div className="row">
                    <button className="btn btn-ghost" onClick={() => remove(a)}>
                      删除
                    </button>
                    <button className="btn" onClick={() => setEditing(a)}>
                      编辑
                    </button>
                  </div>
                </div>
                <div className="small muted">
                  发布于 {formatTime(a.publishedAt)}
                  {a.updatedAt !== a.publishedAt && <> · 编辑于 {formatTime(a.updatedAt)}</>}
                </div>
                <p className={`announcement-content ${long && !open ? 'clamped' : ''}`}>{a.content}</p>
                {long && (
                  <button type="button" className="link-btn small" onClick={() => toggle(a.id)}>
                    {open ? '收起' : '展开全文'}
                  </button>
                )}
              </article>
            )
          })}
      </div>
      {editing && (
        <AnnouncementEditor
          initial={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            announcements.reload()
          }}
        />
      )}
    </>
  )
}

function AnnouncementEditor({ initial, onClose, onSaved }: { initial: Announcement | null; onClose: () => void; onSaved: () => void }) {
  const toast = useToast()
  const [value, setValue] = useState<AnnouncementInput>({
    title: initial?.title ?? '',
    content: initial?.content ?? '',
    pinned: initial?.pinned ?? false,
  })
  const [busy, setBusy] = useState(false)
  const set = (patch: Partial<AnnouncementInput>) => setValue((v) => ({ ...v, ...patch }))
  const titleLength = length(value.title.trim())
  const contentLength = length(value.content.trim())
  const valid = titleLength > 0 && titleLength <= MAX_TITLE && contentLength > 0 && contentLength <= MAX_CONTENT

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      if (initial) await api.updateAnnouncement(initial.id, value)
      else await api.createAnnouncement(value)
      toast.ok(initial ? '已保存' : '已发布')
      onSaved()
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={initial ? `编辑公告：${initial.title}` : '发布公告'} onClose={onClose} wide>
      <form className="form" onSubmit={save}>
        <Field label="标题" hint={<span className={titleLength > MAX_TITLE ? 'text-warn' : ''}>{titleLength} / {MAX_TITLE}</span>}>
          <input value={value.title} onChange={(e) => set({ title: e.target.value })} placeholder="例如：枪战感染新地图上线" autoFocus required />
        </Field>
        <Field
          label="内容"
          hint={
            <span className={contentLength > MAX_CONTENT ? 'text-warn' : ''}>
              {contentLength} / {MAX_CONTENT} · 纯文本，换行会保留
            </span>
          }
        >
          <textarea rows={10} value={value.content} onChange={(e) => set({ content: e.target.value })} required />
        </Field>
        <label className="check">
          <input type="checkbox" checked={value.pinned} onChange={(e) => set({ pinned: e.target.checked })} />
          置顶（在公告列表中排在最前）
        </label>
        {initial && <p className="muted small">修改不会改变发布时间；已读过的玩家不会再次收到提醒。</p>}
        <div className="row end">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary" disabled={busy || !valid}>
            {busy ? '保存中…' : initial ? '保存' : '发布'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
