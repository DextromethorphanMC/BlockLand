import { Link } from 'react-router-dom'
import { api } from '../api'
import { ErrorBox, PageHeader, useLoad } from '../components/ui'

export function Dashboard() {
  const { data, error } = useLoad(api.overview)

  return (
    <>
      <PageHeader title="概览" subtitle="所有修改会在 Minecraft 服务器下一次同步时生效（默认 30 秒，或在游戏内执行 /bl reload）" />
      <ErrorBox error={error} />
      {data && (
        <div className="stat-grid">
          <Link to="/modes" className="stat">
            <span className="stat-value">
              {data.enabledModes}
              <small>/{data.modes}</small>
            </span>
            <span className="stat-label">已启用的游戏模式</span>
          </Link>
          <Link to="/maps" className="stat">
            <span className="stat-value">{data.maps}</span>
            <span className="stat-label">地图</span>
          </Link>
          <Link to="/users" className="stat">
            <span className="stat-value">
              {data.onlinePlayers}
              <small>/{data.players}</small>
            </span>
            <span className="stat-label">在线 / 全部玩家</span>
          </Link>
          <div className="stat">
            <span className="stat-value">v{data.contentVersion}</span>
            <span className="stat-label">配置版本</span>
          </div>
        </div>
      )}

      <section className="card">
        <h2>连接 Minecraft 服务器</h2>
        <p className="muted">
          {data && data.knownItems > 0
            ? `已收到服务器上报的 ${data.knownItems} 个物品 ID，装备与物资表会自动补全与校验。`
            : '尚未有服务器连接过后端。启动安装了 BlockLand 模组的服务器后，这里会显示服务器上报的物品数量。'}
        </p>
        <p>
          在服务器的 <code>config/blockland-common.toml</code> 中设置：
        </p>
        <pre className="code">{`[backend]
url = "${window.location.origin.replace(':5173', ':8080')}"
serverToken = "与 application.yml 中 blockland.server-token 相同"`}</pre>
        <h3>游戏内命令</h3>
        <ul className="cmd-list">
          <li><code>M</code> 键 / <code>/bl lobby</code> — 打开游戏大厅</li>
          <li><code>/bl leave</code> — 离开对局返回大厅</li>
          <li><code>/bl reload</code> — 立即从后端同步配置（OP）</li>
          <li><code>/bl start &lt;模式ID&gt;</code> — 用队列中的玩家强制开局，可单人测试（OP）</li>
          <li><code>/bl map preview &lt;地图ID&gt;</code> 与 <code>/bl map here</code> — 预览地图并查询相对坐标，用于设置出生点和箱子（OP）</li>
        </ul>
      </section>
    </>
  )
}
