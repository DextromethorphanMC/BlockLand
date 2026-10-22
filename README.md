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
| 枪械 · 数据 | `data/blockland/guns/guns.json` + `gun/GunCatalog` / `GunStats` | 弹药、弹匣、瞄具、激光、皮肤、枪械全部是数据；伤害/散布/后坐力/换弹时间等有效数值只在 `GunStats` 计算，客户端与服务端共用同一份 |
| 枪械 · 服务端 | `server/gun/GunService` | 唯一权威：校验每一枪（当前栏位、射速、余弹），用与客户端相同的散布种子做射线判定、爆头与距离衰减，计时换弹并扣除背包弹药，校验枪匠改装 |
| 枪械 · 客户端 | `client/gun/*`（输入、预测、HUD、枪匠）、`client/gltf/*`（GLB 解析与动画采样）、`client/render/*`（GPU 渲染） | 客户端只做表现与预测；美术资源只通过 `tools/assets/VIEWMODEL_SPEC.md` 约定的节点名/动画名/材质名接入，换成美术制作的 GLB 无需改代码 |
| 第一人称 · 共用 | `client/viewmodel/*`（`ViewmodelRig`、`ViewmodelMotion`、`ViewmodelRenderer`） | 所有武器共用的部分：GLB 动画层（待机 / 单次动作 / 交叉淡化 / 疾跑循环）、相机节点、玩家皮肤手臂、程序化运动与独立视场角渲染；枪械（瞄准、后坐力、套筒、枪口火焰、激光）与刀具（命中回弹、镜头冲击）只在各自的视图模型里叠加 |
| 武器输入 | `client/input/WeaponInput` | 左/右键的真实按下沿与共用的检视键（`I`）；枪械与刀具各自订阅，只在自己的武器在手时响应 |
| 刀具 · 数据 / 服务端 | `data/blockland/melee/knives.json` + `melee/*`，`server/melee/MeleeService` | 轻击 / 重击的伤害、距离、范围、出刀与命中时间、冷却、击退、背刺倍率、连击动画与切痕方向全部是数据；`MeleeTrace` 由服务端（伤害）与客户端（即时特效）共用。服务端唯一权威：校验栏位与冷却，在命中时刻重新判定并结算伤害，与枪械模块互不依赖（独立的 `MeleePacketListeners`） |
| 刀具 · 客户端 | `client/melee/*` | 输入（左键轻击可按住连斩、右键重击、检视）、连击交替、按键缓冲、命中时刻的本地特效与镜头冲击；共用 `client/hud/HitMarker` |
| 损伤贴图 | `client/decal/*` + `assets/blockland/decals.json` | 与武器无关：武器只调用 `Decals.onBlock` / `Decals.onEntity` 并传入数据里的贴图类型 id。每个贴图有自己的存在时间（类型时长 ± 随机）并在末尾淡出，每类有数量上限；方块贴图贴合并裁剪到被击中的面（同平面的相邻方块会延伸），方块变化时消失；身体贴图跟随人形模型的部位（头、躯干、四肢）。按贴图纹理一批一次绘制，带深度偏移不闪烁 |
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
- **限时模式**：小游戏可设置「限时开放」截止时间，到期后自动关闭（正在排队的玩家会被移出队列，进行中的对局不受影响）。大厅中限时模式带金色边框与「限时 · 剩余 N 天」标签（最后两天闪烁），分组卡片显示「限时」，右下角详情显示截止时间。
- **转让队长**：队长把鼠标移到队员头像上，点左上角的 ★ 即可转让；正在进行的匹配不会被取消。
- **枪械系统**（首把枪：**P9 手枪**）
  - 操作：左键射击，右键瞄准（ADS），`R` 换弹，`I` 检视，`G` 打开枪匠，`B` 切换射击模式（均可在按键设置中修改）。
  - 射击模式：每把枪声明支持的模式（`guns.json` 的 `fireModes`，第一个为默认）。**P9 手枪**仅半自动（每按一次左键一发，按住挖方块也不会连发）；**P9A 冲锋手枪**默认全自动（约 1050 发/分），可按 `B` 切换为半自动。
  - 弹药：手枪 / 冲锋枪 / 霰弹枪 / 狙击枪弹药（物品，每种有自己的伤害、爆头倍率与适配弹匣）；弹匣：标准 / 扩容（适配枪械、容量、换弹与开镜速度）。换弹从背包扣除对应弹药，创造模式不消耗。
  - 每把枪有自己的名称、GLB 模型、皮肤、检视与换弹动画、射速、后坐力、腰射/瞄准精度、距离衰减；全部在 `guns.json` 中配置。
  - 爆头：命中头部（眼睛略下方以上）伤害乘以弹药的爆头倍率；命中/爆头/击杀有不同的命中标记与音效。
  - 枪匠：手持枪械按 `G`，实时 3D 预览（拖动旋转、滚轮缩放），切换瞄具、激光、弹匣、皮肤，属性条显示改动带来的变化；改装保存在客户端（`config/blockland-gun-loadouts.json`），对局发放的枪会自动套用，服务端逐项校验。
  - 第一人称动作：拿枪时完全不使用原版的行走晃动（view bobbing），改为 COD/战地式的程序化动作——随脚步的 8 字形武器晃动、横移倾斜、视角惯性、起跳/落地、蹲伏、呼吸，以及与之同步的轻微摄像机晃动；疾跑时播放战术疾跑动画（枪口朝上举在脸侧、另一只手随步伐摆动），从疾跑中开火/开镜要先放下枪（短暂延迟），瞄准、射击或检视时不能疾跑（开始检视会立即停止疾跑）。
  - 表现：GLB 第一人称视图模型（玩家皮肤的手臂、相机空间动画、动画事件驱动音效），程序化瞄准对齐、弹簧后坐力与镜头震动；枪口火焰、抛壳（带物理与落地声）、曳光弹、弹孔碎屑、激光束与激光点、红点准镜分划。
  - GPU 优化：模型在加载时一次性上传到 GPU（单顶点缓冲 + 单索引缓冲，24 字节紧凑顶点），每帧只用一次 `writeTransforms` 写入所有节点的变换并在一个渲染通道内完成全部绘制；动画采样与矩阵计算无分配；第三人称/背包使用原版烘焙的低模（LOD），不对其他玩家做逐帧自定义绘制；枪匠预览只在构型或角度变化时重绘；视图模型使用独立的 54° 视场角（与射击游戏一致，枪械更大、畸变更小）。
  - 实测（RTX 5090 Laptop、854×480、不限帧率）：完整改装的视图模型 28 次绘制、约 7.5k 三角形、CPU 0.05 ms/帧（F3 调试界面左侧显示 `[BlockLand] viewmodel` 实时数据）；同一画面手持枪械与空手的帧时间差约 0.13 ms，60 FPS 下约占 0.8% 帧预算。
- **刀具系统**（首把刀：**蝴蝶刀**），与枪械同一套技术栈（GLB 视图模型、玩家皮肤手臂、程序化运动、GPU 保留模式渲染）
  - 操作：**左键轻击**（快速左右连斩，两段动画交替，按住持续挥砍），**右键重击**（蓄力后突刺），`I` 检视（蝴蝶刀花式翻转）。轻击 5 伤害、冷却 0.36 秒；重击 11 伤害、冷却 0.95 秒；从背后命中为**背刺**（轻击 ×1.5、重击 ×2，重击背刺可一击击杀满血玩家），命中标记为橙色并提示「背刺」。数值全部在 `knives.json` 配置。
  - 攻击、检视时不能疾跑；切换武器会取消尚未命中的攻击；刀不能挖方块、不能触发原版近战与方块交互。
  - 命中：刀刃命中瞬间（动画中的接触点，与服务端判定时间一致）在客户端即时播放命中音效、血点/碎屑火花、武器回弹与镜头冲击，服务端重新判定后发回命中标记；其他玩家看到挥刀动作并看到命中的切痕。
- **损伤贴图**：子弹击中方块留下**弹孔**（4 种随机样式、随机旋转、约 30 秒），刀击中墙壁留下**刀痕**（轻击为沿挥砍方向的划痕，重击为刺孔，约 22 秒），刀击中人物在被击中的身体部位留下**伤口**（约 10 秒，跟随人物转身与转头）。每个贴图有自己的存在时间（基础时长 ± 随机）并在最后几秒淡出；方块被破坏或改变时其上的贴图消失。外观、尺寸、时长与数量上限在资源包 `assets/blockland/decals.json` 中配置（F3+T 重新加载），F3 左侧显示当前贴图数量。
- **枪战1v1**（FastPVP 示例模式，限时开放至 2026-11-30）：1v1，开局一把 P9 手枪 + 60 发手枪弹药 + 一把蝴蝶刀。
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

### 开发测试驱动

开发客户端可加 `-Pautomation=<目录>` 启动（例如 `.\build.ps1 runClient -PdevName=Steve -Pautomation=C:/tmp/auto`），
客户端会读取 `<目录>/<玩家名>.txt` 中的测试指令（按键、选栏位、执行命令、瞄准某玩家、截图等，见
`client/dev/DevAutomation.java`），结果追加到 `<目录>/<玩家名>.log`。不加该参数时完全不启用，不影响正常游戏。

### 武器美术资源

`tools/assets/` 下的程序化生成器（`python -I tools/assets/build_all.py`）生成 P9 与蝴蝶刀的 GLB、手臂、图标、低模、特效与损伤贴图、音效；
美术制作的模型只要遵守 `tools/assets/VIEWMODEL_SPEC.md`（节点名、相机空间、动画名与事件、材质名），放进资源包即可替换（F3+T 重新加载）。

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
