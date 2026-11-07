# 部署安全：令牌与加密

BlockLand 有三条连接。只要连接离开本机，就必须加密。

| 连接 | 携带的秘密 | 怎么保护 |
| --- | --- | --- |
| 玩家客户端 ↔ Minecraft 服务端 | 玩家会话 | 原版 Minecraft：`online-mode=true` 时登录后整条连接加密（模组通道 `blockland:main` 也在其中）。正式服必须开正版验证；`mod/run-server` 的离线模式只用于开发 |
| Minecraft 服务端 ↔ 后端 | 服务器令牌（`X-Server-Token`，每个请求都带） | 两者在同一台机器上时走 `http://127.0.0.1`（流量不出本机）；不在同一台时用 `https://` |
| 浏览器（管理后台）↔ 后端 | 管理令牌（`Authorization: Bearer`，每个请求都带） | 只能经 HTTPS 访问：放在 HTTPS 反向代理之后，或由后端自己提供 HTTPS |

拿到管理令牌就能修改全部配置，拿到服务器令牌就能冒充游戏服务器（上报对局结果、发放奖励）。所以令牌绝不能以明文经过网络。

## 代码里的保护

不安全的配置启动不起来，或者直接被拒绝：

- **后端只监听本机。** 默认 `server.address: 127.0.0.1`（`server/backend/src/main/resources/application.yml`）。
- **后端拒绝不安全地对外监听。** 监听地址不是本机时（改了 `server.address`，或设为 `0.0.0.0`），以下两条都要满足，否则 `TransportSecurity` 拒绝启动并列出原因：
  - 启用了 HTTPS（`server.ssl.*`），或者显式设置了 `blockland.transport.allow-insecure-remote: true`（只用于完全可信的内网）；
  - 两个令牌都不是默认值，并且至少 24 个字符。
- **弱令牌只在本机直连时有效。** 默认值或过短的令牌，只接受本机直接发出的请求；从其他机器来的、或经反向代理转发的（带 `X-Forwarded-For` / `Forwarded` / `X-Real-IP`）一律返回 403（`TokenAuthFilter`）。所以开发用的默认令牌即使后端被代理出去也用不了。
- **模组服务端只把令牌发给安全地址。** `BackendHttp` 只向 `https://` 地址或本机的 `http://` 地址（`127.x.x.x`、`localhost`、`::1`）发送请求。其他 `http://` 地址的请求一律拒绝，效果等同于后端不可达，服务端继续使用缓存的配置；启动时也会在日志里报错。只有设置 `[backend] allowInsecureRemote = true`（可信内网）才放行。重定向一律不跟随，以免令牌被带到其他主机。
- **安全响应头。** 后端的每个响应都带 `X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`；后端自己提供 HTTPS 时还会加 `Strict-Transport-Security`（`SecurityHeadersFilter`）。

## 推荐部署

### 1. 生成令牌

```bash
python -c "import secrets; print(secrets.token_urlsafe(32))"
```

运行两次，分别得到管理令牌和服务器令牌。它们可以写进后端的 `application.yml`（`blockland.admin-token`、`blockland.server-token`），或者用环境变量设置：`BLOCKLAND_ADMINTOKEN`、`BLOCKLAND_SERVERTOKEN`。服务器令牌还要同样写进 Minecraft 服务端的 `config/blockland-common.toml`：

```toml
[backend]
    url = "http://127.0.0.1:8080"
    serverToken = "<服务器令牌>"
```

不要把真实令牌提交进仓库。

### 2a. 后端与 Minecraft 服务端在同一台机器（推荐）

- 后端保持 `server.address: 127.0.0.1`，Minecraft 服务端用 `http://127.0.0.1:<端口>` 连接。
- 管理后台通过 HTTPS 反向代理访问，以 [Caddy](https://caddyserver.com/) 为例（自动申请并续期证书）：

```
admin.example.com {
    reverse_proxy 127.0.0.1:8080
    header Strict-Transport-Security "max-age=31536000"
}
```

- 防火墙只开放 Minecraft 端口（25565）和 443，不开放后端端口。

### 2b. 后端在另一台机器

由后端自己提供 HTTPS（Spring Boot 直接读取 PEM 证书）：

```yaml
server:
  address: 0.0.0.0
  port: 8443
  ssl:
    certificate: file:/etc/blockland/fullchain.pem
    certificate-private-key: file:/etc/blockland/privkey.pem
```

Minecraft 服务端的 `url` 改成 `https://backend.example.com:8443`。证书由公共 CA 签发时无需额外配置；如果是自签证书，给 Minecraft 服务端的 JVM 加上 `-Djavax.net.ssl.trustStore=<含该证书的信任库> -Djavax.net.ssl.trustStorePassword=<密码>`。

也可以在后端那台机器上同样放一个反向代理（像 2a 那样），后端保持只监听本机。

## 还没有覆盖的风险

- **管理令牌是所有管理员共用的一个固定值。** 没有个人账号、没有过期时间，也不记录是谁操作的。换令牌后所有管理员需要重新输入。
- **管理令牌保存在浏览器的 `localStorage`。** 如果后台页面被注入脚本（XSS），令牌会被读走。
- **令牌校验失败没有次数限制。** 随机 32 字节的令牌无法暴力猜出，但如果用的是短令牌就有风险；对外时强制至少 24 个字符就是为此。
