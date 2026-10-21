# BlockLand

Minecraft 1.21.8 / Forge 58.1.26 小游戏服务器：CS2 风格游戏大厅 + 空岛战争 + 快速PVP（含 ClassPVP），配置由 Java 后端 + React 后台管理。

```
BlockLand/
├─ mod/              Forge 模组（单一模组 blockland，客户端与服务端共用一个 jar）
└─ server/
   ├─ backend/       Spring Boot 3.5 (Java 21) REST API，JSON 文件存储
   └─ web/           React 19 + Vite 管理后台
```

## 架构

```
React 后台 ──/api/admin (Bearer admin-token)──▶ Spring Boot 后端 ◀──/api/server (X-Server-Token)── Forge 服务器
                                                  data/*.json + data/maps/*          (每 30 秒按 ETag 拉取配置)
Forge 服务器 ◀──SimpleChannel──▶ 客户端大厅界面
```

低耦合要点：

| 层 | 抽象 | 说明 |
|---|---|---|
| 模组 · 游戏类型 | `server/game/GameType` + `GameTypeRegistry` | 大厅、匹配、对局管理只认识接口；新增小游戏只需实现并注册一个 `GameType` |
| 模组 · 团队竞技场 | `games/common/TeamArenaGame` | 地图粘贴、玻璃笼倒计时、淘汰、胜负/限时、计分板队伍等通用流程；`games/skywars`、`games/fastpvp` 只通过钩子添加各自逻辑 |
| 模组 · 开局装备 | `server/kit/KitService` + `KitLayoutStore` | 任何游戏类型都可使用的开局装备。布局由客户端保存，排队时随请求发送；服务端只校验它是预设的重新排列（物品相同、盔甲不动），发放的永远是服务端预设的物品 |
| 玩家 / 权益 | 后端 `player/PlayerService`，模组 `server/player/PlayerProfiles` | 在线时长（服务器每 15 秒上报）、封禁、禁言、权益。商店只「授予权益」，匹配只「询问权益」，两者互不依赖 |
| 商店 / 支付 | 后端 `shop/ShopService` + `payment/PaymentProvider`，模组 `server/shop/ShopRelay` | 支付走 `PaymentProvider` 接口（当前 `MockPaymentProvider` 直接成功），接入真实支付只需新增实现；客户端不直接访问后端，商品由游戏服务器转发 |
| 图片 / 封面 | 后端 `media/MediaService`，模组 `server/media/MediaRelay` + 客户端 `ClientMedia` | 商品图片、模式封面、分组封面共用一套「命名空间 + id + 版本」存储与转发；业务模块只保存版本号，客户端按版本缓存纹理 |
| 模式分组 | 后端 `modegroup/*`，模组 `ModeGroupDef` / `LobbyGroupEntry` | 分组只引用模式 id，不改变模式本身；删除模式时自动从分组中移除；在后台「小游戏管理」中维护 |
| 组队 | 模组 `server/party/PartyService` + `PartyPackets` | 组队只管成员与邀请，通过监听器通知变化；匹配只接收「必须同队的一组玩家」，对局只接收 `MatchRoster`（已分好的队伍），三者互不依赖 |
| 大厅世界 | 模组 `server/lobby/LobbyWorld` | 大厅玩家位于空的 `blockland:lobby` 维度，主世界不保留出生点区块；可用配置 `lobby.voidWorld=false` 改回主世界 |
| 模组 · 动画 | `anim/*`（纯 Java，无 Minecraft 依赖） | GSAP 风格：`Tween`、`Timeline`（位置参数 `<` `>` `+=` 标签）、`Ease`（含 `parse("back.out(1.7)")`、cubic-bezier）、`Stagger`、`Spring`、OKLab 颜色插值；UI 只通过 `AnimValue` / `Spring` 读值 |
| 模组 · 大厅界面 | `client/screen/LobbyScreen` + `client/screen/lobby/*` | 屏幕只负责布局与连线；`TopBar`、`ModeBrowser`、`MatchPanel`、`CharacterStage` 各自持有动画状态，`client/ui/Draw` 提供光晕、渐变、封面裁切等绘制 |
| 小游戏专属数据 | 地图 `settings` + 同步接口 `typeData` | 物资表、箱子坐标只属于空岛战争（后端 `gametype/skywars`、模组 `games/skywars`、前端 `gametypes/skywars`），其他小游戏完全不依赖 |
| 模组 · 配置来源 | `server/content/ContentRepository` | 当前为 HTTP 实现 `RemoteContentRepository`（带磁盘缓存，后端离线时继续用上次配置） |
| 模组 · 地图格式 | `server/map/MapFormat` | `.nbt`（原版结构，自动 DataFixer 升级）与 `.schem`（Sponge v2/v3）→ 统一的 `MapBlueprint` |
| 模组 · 网络 | `network/ServerPacketListener` / `ClientPacketListener` | 网络层不引用服务端或客户端逻辑；客户端代码不会在专用服务器加载 |
| 后端 · 游戏类型 | `gametype/GameTypeDefinition` | 每种游戏类型负责校验自己的 `settings` |
| 后端 · 存储 | `storage/Repository<T>` | 当前为 JSON 文件实现，可替换为数据库 |
| 前端 · 游戏类型 | `src/gametypes/index.tsx` | 每种类型一个设置表单；未知类型自动退化为 JSON 编辑 |

## 功能

- **游戏大厅**（按 `M` 或 `/bl lobby`，进服与对局结束后自动打开）：左侧「选择游戏模式」先显示分组卡片（封面、标题、介绍、模式数）与未分组的模式，点开分组后只显示该组模式（「‹ 全部模式」或 `ESC` 返回）；模式卡片右侧显示封面。右侧为玩家人物模型（固定角度、呼吸浮动），右下角「开始匹配 / 取消匹配」与计时；顶栏显示总游玩时间与在线人数，「商店」页可购买商品（模拟支付）。全部动效使用模组自带动画系统：开场依次入场、卡片弹簧悬停/按压、分组切换滑动 + 交错入场、按钮颜色过渡与匹配中扫光、计时跳动。
- **小游戏管理**：添加模式（Title、游戏类型、介绍、封面（16:9，自动缩放到 512×288）、启用、可选存活时间），首次启动自动生成 **空岛战争 1v1 / 4v4** 与 **ClassPVP**（含示例封面）。
- **模式分组**（后台「小游戏管理」页内，模式按分组列出）：封面、Title、介绍、排序、启用以及有序的小游戏列表；编辑模式时可直接选择「所属分组」。示例分组「空岛战争」（1v1、4v4）与「PVP 竞技」（ClassPVP）。
- **组队**：大厅右上角显示队伍成员头像（★ 队长，颜色表示大厅 / 匹配中 / 对局中），点「+」输入玩家名称邀请；被邀请者在大厅中看到邀请卡片（接受 / 拒绝，60 秒有效）。只有队长可以开始匹配，整队一起排队并分在同一队伍；队员可取消匹配；成员变化会自动取消当前匹配。队伍人数超过模式每队人数时无法匹配。上限由配置 `party.maxSize`（默认 4）决定。
- **轻量大厅**：进服后玩家直接位于空的大厅维度，只显示大厅界面，主世界不加载区块。
- **开局装备 / 装备布局**：支持开局装备的游戏类型（目前为快速PVP）在后台按背包格子配置装备；玩家在大厅选中模式后点「装备布局」可自行调整物品位置（盔甲固定），保存在**客户端**（`config/blockland-kit-layouts.json`），排队时发送给服务器校验；管理员修改装备后旧布局自动失效。
- **快速PVP（FASTPVP）**：自定义开局装备、是否允许破坏/放置方块、可选限时、每队人数 × 队伍数、自定义地图；淘汰后不掉落装备。示例模式 **ClassPVP**（1v1，铁套 + 铁剑，示例竞技场地图）。
- **用户（后台）**：玩家列表、在线状态、总在线时间、最后在线；封禁 / 禁言（原因 + 时长或永久，在线玩家 15 秒内被踢出或禁止聊天）；手动授予 / 收回权益。
- **商店（后台）**：商品包含名称、图片（自动转 PNG ≤256px，游戏内同步显示）、介绍、价格、分类、权益标识与有效期；订单列表。首个商品 **优先匹配排队**（¥9.90 / 30 天，授予 `PRIORITY_QUEUE`，重复购买顺延）。
- **优先匹配排队**：拥有 `PRIORITY_QUEUE` 权益的玩家在所有队列中排在最前面（同类按加入顺序）；大厅匹配面板显示「★ 优先匹配」，OP 可用 `/bl queue <模式ID>` 查看队列顺序。
- **空岛战争**
  - 导入地图：上传 `.nbt` / `.schem`，自动识别尺寸、所有箱子/木桶坐标，可选「出生点标记方块」自动生成出生岛（相邻标记归为同一队）。
  - 匹配人数：每队人数 × 每局队伍数（预设 1v1 / 2v2 / 4v4 / 8 人混战等）。
  - 物资：物资表中每个物品可设置数量范围与出现概率，支持 `/give` 组件语法（附魔等）；每箱最少/最多物品数；后台可「模拟开箱」。
  - 按坐标为每个箱子单独指定物资表（俯视图点选 + 批量设置），未指定的使用地图默认物资表；地图中未登记的箱子也会使用默认物资表。
  - 对局流程：加载并分批粘贴地图到独立虚空维度 `blockland:arena` → 玻璃笼倒计时 → 开战（队友无伤、虚空判定、击杀播报、可选箱子刷新）→ 最后存活队伍获胜 / 到达存活时间按存活人数判定 → 结算后返回大厅并清理场地。

## 运行

### 1. 后端 + 管理后台

```bash
cd server/web && npm install && npm run build
```

```bash
cd server/backend && mvn package
```

```bash
cd server/backend && java -jar target/blockland-backend-0.1.0.jar
```

打开 <http://localhost:8080>，使用 `application.yml` 中的 `blockland.admin-token` 登录。**上线前请修改 `admin-token` 与 `server-token`。** 数据保存在 `server/backend/data/`。

前端开发：后端运行时执行 `npm run dev`（<http://localhost:5173>，`/api` 自动代理到 8080）。

### 2. 模组

本机默认 `JAVA_HOME` 是 JDK 8，Gradle 9 需要 17+；`build.ps1` 会自动改用 JDK 26，并绕过 JDK 26 在本机 Windows 上的 NIO 管道问题（`Unable to establish loopback connection`）。编译用的 Java 21 工具链由 Gradle 自动下载。

```bash
cd mod && powershell -ExecutionPolicy Bypass -File build.ps1 build
```

产物：`mod/build/libs/blockland-0.1.0.jar`，服务端与客户端都需要安装。

开发测试（`mod/run-server` 已配置为离线模式 + 超平坦 + 仅本机 RCON）：

```bash
cd mod && powershell -ExecutionPolicy Bypass -File build.ps1 runServer
```

```bash
cd mod && powershell -ExecutionPolicy Bypass -File build.ps1 runClient -PjoinServer=localhost:25565 -PdevName=Steve
```

**服务器地址**：默认 `localhost:25565`（`server-ip` 为空 = 监听所有网卡，局域网内其他电脑用本机 IP:25565）。开发服务器是离线模式，同时开两个客户端只需名字不同，第二个客户端用独立的游戏目录：

```bash
cd mod && powershell -ExecutionPolicy Bypass -File build.ps1 runClient -PjoinServer=localhost:25565 -PdevName=Alex -PclientDir=run2
```

`build.ps1` 会把系统的 `HTTPS_PROXY`（如本机 Clash `127.0.0.1:7890`）转换为 Java 代理参数，否则 Mojang 资源下载会超时。

服务器配置 `config/blockland-common.toml`：

```toml
[backend]
url = "http://127.0.0.1:8080"
serverToken = "change-me-server"
syncIntervalSeconds = 30
```

开发服务器的 `ops.json` 已将开发客户端玩家 **Steve** 设为 4 级 OP（离线模式 UUID），用 `-PdevName=Steve` 启动客户端即可直接使用 OP 命令。

### 游戏内命令

| 命令 | 说明 |
|---|---|
| `M` / `/bl lobby` | 打开大厅 |
| `/bl join <模式ID>` | 用命令加入匹配队列（等同于大厅里的「开始匹配」，使用默认装备布局） |
| `/bl queue <模式ID>` (OP) | 查看队列顺序（★ = 优先匹配） |
| `/bl leave` | 离开对局（阵亡后观战时也可用）并返回大厅 |
| `/bl reload` (OP) | 立即同步后端配置 |
| `/bl status` / `/bl games` / `/bl stopall` (OP) | 状态、进行中对局、中止全部对局 |
| `/bl start <模式ID>` (OP) | 用队列中的玩家立即开局（可单人测试） |
| `/bl map preview <地图ID>` / `/bl map here` / `/bl map close` (OP) | 预览地图并查询脚下/准星方块的**相对坐标**，用于填写出生点与箱子 |

## 坐标约定

地图中所有坐标都相对于结构/schematic 的最小角 `(0,0,0)`。出生点坐标是玩家**脚下所在的方块**；朝向 yaw 与 Minecraft 相同（0 = 南，90 = 西）。队伍按出生岛列表均匀分配，例如 8 个岛的 1v1 使用第 1 和第 5 个岛。
