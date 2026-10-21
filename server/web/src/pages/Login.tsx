import { useState, type FormEvent } from 'react'
import { api, errorText, setToken } from '../api'

export function Login() {
  const [token, setValue] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      await api.login(token.trim())
      setToken(token.trim())
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <form className="login-card" onSubmit={submit}>
        <div className="logo logo-big">
          BLOCK<span>LAND</span>
        </div>
        <p className="muted">小游戏服务器管理后台</p>
        <label className="field">
          <span className="field-label">管理令牌</span>
          <input
            type="password"
            autoFocus
            value={token}
            onChange={(e) => setValue(e.target.value)}
            placeholder="application.yml 中的 blockland.admin-token"
          />
        </label>
        {error && <div className="error-box">{error}</div>}
        <button className="btn btn-primary btn-block" disabled={busy || !token.trim()}>
          {busy ? '验证中…' : '进入后台'}
        </button>
      </form>
    </div>
  )
}
