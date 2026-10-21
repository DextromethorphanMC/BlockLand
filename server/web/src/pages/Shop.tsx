import { useRef, useState, type FormEvent } from 'react'
import { api, productImageUrl } from '../api'
import { confirmAction, Empty, ErrorBox, Field, Modal, NumberInput, PageHeader, useLoad, useToast } from '../components/ui'
import type { Product } from '../types'
import { formatTime } from './Users'

export function formatPrice(cents: number, currency = 'CNY') {
  return `${currency === 'CNY' ? '¥' : currency + ' '}${(cents / 100).toFixed(2)}`
}

export function Shop() {
  const toast = useToast()
  const products = useLoad(api.products)
  const orders = useLoad(api.orders)
  const [editing, setEditing] = useState<{ product: Product; isNew: boolean } | null>(null)

  const remove = async (p: Product) => {
    if (!confirmAction(`确定删除商品「${p.name}」？已售出的权益不受影响。`)) return
    try {
      await api.deleteProduct(p.id)
      toast.ok('已删除')
      products.reload()
    } catch (e) {
      toast.error(e)
    }
  }

  const paid = (orders.data ?? []).filter((o) => o.status === 'PAID')
  const revenue = paid.reduce((sum, o) => sum + o.priceCents, 0)

  return (
    <>
      <PageHeader
        title="商店"
        subtitle="玩家在游戏大厅的「商店」页购买商品，购买后获得商品对应的权益（如 PRIORITY_QUEUE = 优先匹配排队）。当前为模拟支付。"
        actions={
          <button
            className="btn btn-primary"
            onClick={() =>
              setEditing({
                isNew: true,
                product: { id: '', name: '', description: '', category: '特权', priceCents: 990, currency: 'CNY', entitlement: '', durationDays: 30, enabled: true, sortOrder: 0, imageVersion: null },
              })
            }
          >
            + 添加商品
          </button>
        }
      />
      <ErrorBox error={products.error} />
      {products.data?.length === 0 && <Empty>还没有商品。</Empty>}
      <div className="product-grid">
        {products.data?.map((p) => (
          <article key={p.id} className={`product-card ${p.enabled ? '' : 'disabled'}`}>
            <div className="product-image">{productImageUrl(p) ? <img src={productImageUrl(p)!} alt={p.name} /> : <span className="muted small">无图片</span>}</div>
            <div className="product-body">
              <div className="row between">
                <span className="tag">{p.category}</span>
                {!p.enabled && <span className="badge">已下架</span>}
              </div>
              <h3>{p.name}</h3>
              <p className="muted small">{p.description}</p>
              <div className="row between">
                <span className="price">{formatPrice(p.priceCents, p.currency)}</span>
                <span className="small muted">
                  <code>{p.entitlement}</code> · {p.durationDays > 0 ? `${p.durationDays} 天` : '永久'}
                </span>
              </div>
              <div className="row end">
                <button className="btn btn-ghost" onClick={() => remove(p)}>
                  删除
                </button>
                <button className="btn" onClick={() => setEditing({ product: p, isNew: false })}>
                  编辑
                </button>
              </div>
            </div>
          </article>
        ))}
      </div>

      <section className="card" style={{ marginTop: 18 }}>
        <div className="row between">
          <h2>订单</h2>
          <span className="muted small">
            成功 {paid.length} 笔 · 合计 {formatPrice(revenue)}
          </span>
        </div>
        {orders.data?.length === 0 && <p className="muted">还没有订单。</p>}
        {orders.data && orders.data.length > 0 && (
          <div className="table-scroll">
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>玩家</th>
                  <th>商品</th>
                  <th>金额</th>
                  <th>状态</th>
                  <th>支付</th>
                </tr>
              </thead>
              <tbody>
                {orders.data.map((o) => (
                  <tr key={o.id}>
                    <td className="small">{formatTime(o.createdAt)}</td>
                    <td>{o.playerName}</td>
                    <td>{o.productName}</td>
                    <td>{formatPrice(o.priceCents, o.currency)}</td>
                    <td>{o.status === 'PAID' ? <span className="badge badge-ok">已支付</span> : <span className="badge badge-danger">失败</span>}</td>
                    <td className="small muted">
                      {o.paymentProvider} · <code>{o.paymentReference}</code>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {editing && (
        <ProductEditor
          initial={editing.product}
          isNew={editing.isNew}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            products.reload()
          }}
        />
      )}
    </>
  )
}

function ProductEditor({ initial, isNew, onClose, onSaved }: { initial: Product; isNew: boolean; onClose: () => void; onSaved: () => void }) {
  const toast = useToast()
  const [p, setP] = useState<Product>(initial)
  const [image, setImage] = useState<File | null>(null)
  const [preview, setPreview] = useState<string | null>(productImageUrl(initial))
  const [busy, setBusy] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const set = (patch: Partial<Product>) => setP((v) => ({ ...v, ...patch }))

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    try {
      const saved = isNew ? await api.createProduct(p) : await api.updateProduct(p)
      if (image) await api.uploadProductImage(saved.id, image)
      toast.ok('已保存')
      onSaved()
    } catch (err) {
      toast.error(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal title={isNew ? '添加商品' : `编辑商品：${initial.name}`} onClose={onClose} wide>
      <form className="form" onSubmit={save}>
        <div className="product-editor">
          <div>
            <button type="button" className="product-image product-image-edit" onClick={() => fileInput.current?.click()}>
              {preview ? <img src={preview} alt="" /> : <span className="muted small">点击上传图片</span>}
            </button>
            <input
              ref={fileInput}
              type="file"
              accept="image/*"
              hidden
              onChange={(e) => {
                const file = e.target.files?.[0] ?? null
                setImage(file)
                if (file) setPreview(URL.createObjectURL(file))
              }}
            />
            <p className="muted small" style={{ maxWidth: 160 }}>
              自动转为 PNG 并缩放到 256px 以内，游戏内也会显示。
            </p>
          </div>
          <div className="form" style={{ flex: 1 }}>
            <div className="grid-2">
              <Field label="名称">
                <input value={p.name} onChange={(e) => set({ name: e.target.value })} required />
              </Field>
              <Field label="商品 ID">
                <input value={p.id} disabled={!isNew} onChange={(e) => set({ id: e.target.value.toLowerCase() })} placeholder="priority_queue" required />
              </Field>
              <Field label="分类">
                <input value={p.category} onChange={(e) => set({ category: e.target.value })} placeholder="特权" />
              </Field>
              <Field label="价格（元）">
                <input
                  type="number"
                  min={0}
                  step={0.01}
                  value={(p.priceCents / 100).toFixed(2)}
                  onChange={(e) => set({ priceCents: Math.round(Number(e.target.value || 0) * 100) })}
                />
              </Field>
              <Field label="权益标识" hint="购买后授予玩家；PRIORITY_QUEUE = 优先匹配排队">
                <input list="known-entitlements" value={p.entitlement} onChange={(e) => set({ entitlement: e.target.value.toUpperCase() })} required />
              </Field>
              <Field label="有效期（天）" hint="0 = 永久；重复购买会顺延">
                <NumberInput value={p.durationDays} min={0} max={3650} onChange={(v) => set({ durationDays: v })} />
              </Field>
              <Field label="排序" hint="越小越靠前">
                <NumberInput value={p.sortOrder} onChange={(v) => set({ sortOrder: v })} />
              </Field>
              <Field label="状态">
                <label className="check">
                  <input type="checkbox" checked={p.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
                  上架销售
                </label>
              </Field>
            </div>
            <datalist id="known-entitlements">
              <option value="PRIORITY_QUEUE" />
            </datalist>
            <Field label="介绍">
              <textarea rows={3} value={p.description} onChange={(e) => set({ description: e.target.value })} />
            </Field>
          </div>
        </div>
        <div className="row end">
          <button type="button" className="btn btn-ghost" onClick={onClose}>
            取消
          </button>
          <button className="btn btn-primary" disabled={busy}>
            {busy ? '保存中…' : '保存'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
