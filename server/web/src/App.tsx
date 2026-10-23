import { useEffect, useState } from 'react'
import { BrowserRouter, NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { getToken, setToken } from './api'
import { ToastProvider } from './components/ui'
import { Announcements } from './pages/Announcements'
import { Dashboard } from './pages/Dashboard'
import { GameModes } from './pages/GameModes'
import { Login } from './pages/Login'
import { allTypePages } from './gametypes'
import { MapEditor } from './pages/MapEditor'
import { Maps } from './pages/Maps'
import { Shop } from './pages/Shop'
import { Users } from './pages/Users'

export function App() {
  const [token, setTokenState] = useState(getToken())

  useEffect(() => {
    const sync = () => setTokenState(getToken())
    window.addEventListener('blockland:auth', sync)
    return () => window.removeEventListener('blockland:auth', sync)
  }, [])

  return (
    <ToastProvider>
      {token ? (
        <BrowserRouter>
          <div className="shell">
            <aside className="sidebar">
              <div className="logo">
                BLOCK<span>LAND</span>
              </div>
              <nav>
                <NavLink to="/" end>
                  概览
                </NavLink>
                <NavLink to="/modes">小游戏管理</NavLink>
                <NavLink to="/maps">地图管理</NavLink>
                <NavLink to="/announcements">公告</NavLink>
                <NavLink to="/users">用户</NavLink>
                <NavLink to="/shop">商店</NavLink>
                {allTypePages().map((p) => (
                  <NavLink key={p.path} to={p.path}>
                    {p.label}
                  </NavLink>
                ))}
              </nav>
              <button className="btn btn-ghost logout" onClick={() => setToken(null)}>
                退出登录
              </button>
            </aside>
            <main className="content">
              <Routes>
                <Route path="/" element={<Dashboard />} />
                <Route path="/modes" element={<GameModes />} />
                <Route path="/maps" element={<Maps />} />
                <Route path="/maps/:id" element={<MapEditor />} />
                <Route path="/announcements" element={<Announcements />} />
                <Route path="/users" element={<Users />} />
                <Route path="/shop" element={<Shop />} />
                {allTypePages().map((p) => (
                  <Route key={p.path} path={p.path} element={p.element} />
                ))}
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </main>
          </div>
        </BrowserRouter>
      ) : (
        <Login />
      )}
    </ToastProvider>
  )
}
