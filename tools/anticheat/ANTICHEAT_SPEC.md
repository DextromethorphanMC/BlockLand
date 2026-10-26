# BlockLand 反作弊规范（ANTICHEAT_SPEC）

模组服务端（`com.blockland.server.anticheat`）、后端（`server/backend` 的 `anticheat` 包）与管理后台（`server/web`）之间的约定。
新增检测、新的移动机制、新的游戏类型或武器都按 §11 接入，不改动其他模块的内部实现。

## §1 原则

- **服务端权威，客户端零改动**：检测全部在服务端完成，原版客户端也能被检测；不往客户端发任何「反作弊」包。
- **宁漏勿错**：每项检测给出的是「物理上可能的上界 + 宽限」，只有超出所有合法解释才算违规；单次超出只累积 VL（违规值），连续超出才回退 / 踢出。
- **延迟自适应**：凡是依赖时间的判断都用该玩家实测的往返时间与抖动（§5）放宽；延迟再高也不会失效——回退照常执行，只是处罚的置信度按延迟打折、高延迟时不自动封禁。
- **按模式分级**：每个模式选一个预设（严格 / 标准 / 宽松 / 仅记录）并可逐项覆盖（§4），游戏类型给新模式选默认预设。
- **低耦合**：其他模块不认识反作弊的内部：武器 / 动作 / 降落伞只通过 `api/Suspicion` 上报信号、通过 `api/MotionAllowance` 声明自己的移动机制；原版改动只在 `mixin/anticheat`，且 mixin 只转发到 `net/NetHooks`。
- **性能预算**：每名玩家每个移动包 O(1) 运算 + 最多两次碰撞查询；每 tick 只采样少量属性；不在热路径分配对象；证据批量异步上报。`/bl ac stats` 显示实测开销。

## §2 分层

```
             ┌──────────────── 执行与管理层 enforce/ ─────────────────┐
             │ Enforcer：回退、取消、踢出、请求封禁、管理员提醒；AntiCheatCommands │
             └───────────────▲──────────────────────────▲─────────────┘
                             │决定                       │上报（异步批量）
   ┌─────────── 证据与置信度引擎 evidence/ ───────────┐   后端 /api/server/anticheat/*
   │ ViolationLedger（每项 VL + 衰减）、Confidence、   │
   │ EvidenceReporter                                 │
   └──────────▲───────────────────────────────────────┘
              │违规（检测 id、超出量、细节）
   ┌──────────┴──────── 检测 check/ ────────────────────────────────────┐
   │ movement（速度、垂直、着地、击退）  packet（频率、非法值、应答、计时器）│
   │ combat（距离、角度、多目标、点击率 + 武器信号）  world（放置朝向）      │
   └──────▲──────────────────▲─────────────────▲───────────────────────┘
          │包络               │状态              │规则
   物理预测引擎 predict/   玩家状态跟踪层 track/   模式规则引擎 rules/
   （纯函数，可离线测试）  （PlayerTrack、延迟、   （全局配置 + 预设 + 模式覆盖
                           窗口、服务端速度账本）   → RuleSet，按模式缓存）
          ▲                  ▲
          └──── 数据包安全层 net/ ────┘
            NetHooks（mixin 入口）、PacketGuard（BlockLand 包限流，经 BLNetwork.InboundGuard）、
            Transactions（ping / pong 应答与往返时间）、TickClock（客户端 tick 计时）
```

模组目录：

| 目录 | 内容 |
|---|---|
| `server/anticheat/api` | `Suspicion`（信号上报门面）、`AntiCheatSignals`（信号 id 常量）、`MotionAllowance` + `MotionBudget`（移动机制声明） |
| `server/anticheat/net` | `NetHooks`（mixin 入口）、`Arrivals`（网络线程到达时间）、`PacketGuard`（`packets.json` 限流） |
| `server/anticheat/track` | `PlayerTrack`（含在途 ping）、`LatencyStats`、`StateWindow`、`Surroundings`、`ServerVelocity`、`TickClock` |
| `server/anticheat/predict` | `Physics`（原版常量与公式）、`MoveInput`、`MoveEnvelope`、`MovePredictor`（纯 Java，无 Minecraft 依赖） |
| `server/anticheat/check` | `CheckCatalog`（`checks.json`）、`Checks`（索引）、`Judge`（检测上报接口）、`MovementChecks`、`CombatChecks`、`PlaceCheck` |
| `server/anticheat/rules` | `CheckSettings`、`RuleSet`、`RuleResolver` |
| `server/anticheat/evidence` | `ViolationLedger`、`Confidence`、`EvidenceReporter` |
| `server/anticheat/enforce` | `Enforcer`、`AntiCheatCommands` |
| `server/anticheat/AntiCheatService` | 组装以上各层，由 `BlockLandServer` 创建与连线 |
| `mixin/anticheat` | `ServerGamePacketListenerMixin`（移动包、着地重定向、tick 结束包）、`ServerCommonPacketListenerMixin`（pong、发出的速度包）（配置 `blockland.anticheat.mixins.json`，`mixins` 段，服务端也生效） |
| `data/blockland/anticheat/checks.json` | 检测目录：名称、说明、分类、是否受延迟影响、默认设置 |
| `data/blockland/anticheat/packets.json` | BlockLand 自定义包的频率上限 |

## §3 检测目录

`checks.json` 是唯一来源；模组启动后把它发布给后端（§9 `POST /api/server/registry/anticheat`），后台据此显示，不写死。

| id | 分类 | 检测内容 | 处置 | 受延迟影响 |
|---|---|---|---|---|
| `packet.rate` | packet | BlockLand 自定义包超过 `packets.json` 的频率 | 丢弃该包；严重刷包踢出 | 否 |
| `packet.invalid` | packet | 不可能的数值：俯仰角超出 ±90° 等 | 记录 | 否 |
| `packet.transaction` | packet | ping/pong 应答乱序、未知应答、超时不应答 | 超时踢出 | 否 |
| `packet.timer` | packet | 客户端 tick 快于 20/s（`ServerboundClientTickEndPacket` 计时余额），或一个 tick 内多个移动包 | 回退 | 是 |
| `movement.speed` | movement | 水平位移超出物理包络 | 回退 | 是 |
| `movement.vertical` | movement | 上升超出跳跃 / 台阶 / 重力包络、空中悬停 | 回退 | 是 |
| `movement.ground` | movement | 声称着地但脚下没有碰撞（无摔落、水上行走） | 以服务端判定的着地状态为准（摔落伤害照算）+ 回退 | 否 |
| `movement.velocity` | movement | 客户端确认收到服务端击退后，下一次移动没有体现（反击退） | 记录 | 是 |
| `movement.parachute` | movement | 信号：降落伞飞行检查失败（`ParachuteFlightCheck`） | 由降落伞模块回退 | 是 |
| `combat.reach` | combat | 原版近战攻击距离超出（按延迟回溯目标位置） | 超出过多时取消该次攻击 | 是 |
| `combat.attack_angle` | combat | 攻击视野外的目标 | 取消 | 是 |
| `combat.multi_target` | combat | 同一客户端 tick 攻击多个不同目标 | 取消 | 否 |
| `combat.cps` | combat | 攻击频率超过人类上限 | 记录 | 否 |
| `combat.fire_rate` | combat | 信号：射速超过枪械数据（`GunService` 已丢弃该枪） | 记录 | 是 |
| `combat.aim_mismatch` | combat | 信号：射击方向与玩家朝向相差过大 | 记录 | 是 |
| `combat.hit_claim` | combat | 信号：声称命中但回溯后子弹并未穿过目标 | 记录 | 是 |
| `combat.melee_rate` | combat | 信号：刀具冷却未结束就出刀 | 记录 | 是 |
| `world.place_face` | world | 放置方块时点击的面背对玩家（自动搭路） | 记录 | 否 |

VL 的单位由各检测定义（一次明确违规约 1 VL，超出越多越大，最多 5）。`checks.json` 格式：

```json
{
  "checks": {
    "movement.speed": {
      "category": "movement",
      "name": "移动速度",
      "description": "水平位移超出按原版物理、效果、地面摩擦与服务端击退推算的上限。",
      "latencySensitive": true,
      "signal": false,
      "defaults": { "enabled": true, "tolerance": 1.0, "setbackVl": 3, "kickVl": 60, "decay": 0.4, "weight": 0.7, "punish": true }
    }
  }
}
```

`signal: true` 表示由其他模块经 `Suspicion` 上报（反作弊只负责记分与处罚）。

## §4 设置模型

### CheckSettings（每项检测）

| 字段 | 类型 / 范围 | 含义 |
|---|---|---|
| `enabled` | bool | 是否检测 |
| `tolerance` | 0.1–5 | 宽限倍率，乘在该检测所有宽限量上（>1 更宽松） |
| `setbackVl` | 0–1000 | VL 达到后撤销违规动作（回退位置 / 取消攻击）；0 = 每次违规都撤销（对「丢弃 / 取消」类）或从不回退（对 `movement.velocity` 等无法撤销的） |
| `kickVl` | 0–10000 | VL 达到后踢出；0 = 从不 |
| `decay` | 0–100 | 不再违规时每秒减少的 VL |
| `weight` | 0–1 | 计入置信度的权重（§7） |
| `punish` | bool | 是否可以作为自动封禁的依据（否则只进人工复核） |

### 预设（全局配置里的 `presets.<id>`）

```json
{
  "name": "严格",
  "description": "竞技 / 排位模式：宽限更小，可自动封禁",
  "toleranceScale": 0.85,
  "ping": { "maxCompensationMs": 600, "jitterMultiplier": 2.0, "extraTicks": 2, "highPingMs": 300 },
  "confidence": { "alert": 0.5, "review": 0.7, "autoBan": 0.97 },
  "actions": { "setback": true, "kick": true, "autoBan": true },
  "checks": { "movement.speed": { "tolerance": 0.9 } }
}
```

- `toleranceScale` 0.1–5：乘在每项 `tolerance` 上。
- `ping.maxCompensationMs` 0–5000、`jitterMultiplier` 0–10、`extraTicks` 0–40、`highPingMs` 50–5000（§5）。
- `confidence.*` 0–1（`autoBan` 取 1 即永不自动封禁）。
- `actions.setback` / `kick` / `autoBan`：总开关（`autoBan` 还要求全局 `autoBan.enabled`）。
- `checks`：只写要改的检测与字段，未写的取 `checks.json` 的默认值。

内置预设（后端首次启动生成，可在后台修改，不能删除 `standard`）：

| id | 名称 | toleranceScale | ping（max / jitter× / extra / high） | confidence（alert / review / autoBan） | actions（setback / kick / autoBan） |
|---|---|---|---|---|---|
| `strict` | 严格 | 0.85 | 600 / 2 / 2 / 300 | 0.5 / 0.7 / 0.97 | ✓ / ✓ / ✓ |
| `standard` | 标准 | 1.0 | 1000 / 2 / 2 / 400 | 0.6 / 0.8 / 0.99 | ✓ / ✓ / ✗ |
| `relaxed` | 宽松 | 1.5 | 1500 / 2.5 / 3 / 600 | 0.75 / 0.9 / 1.0 | ✓ / ✗ / ✗ |
| `observe` | 仅记录 | 1.0 | 1000 / 2 / 2 / 400 | 0.6 / 0.8 / 1.0 | ✗ / ✗ / ✗ |

### 全局配置（后端 `anticheat-config.json`，随 `/api/server/bootstrap` 的 `antiCheat` 字段下发）

```json
{
  "enabled": true,
  "defaultPreset": "standard",
  "presets": { "strict": { … }, "standard": { … }, "relaxed": { … }, "observe": { … } },
  "autoBan": { "enabled": false, "durationMinutes": 0, "reason": "反作弊系统自动封禁" },
  "alerts": { "staff": true, "cooldownSeconds": 30 },
  "evidence": { "maxPerCase": 100, "retentionDays": 60, "reportCooldownSeconds": 5 },
  "suspicion": { "halfLifeDays": 7, "reviewScore": 3.0 }
}
```

- `defaultPreset`：不在对局中的玩家（管理员「进入世界」）与预设缺失的模式使用。
- `autoBan.durationMinutes` 0 = 永久；`reason` 写进封禁原因，后面自动加案件编号。
- 保存全局配置会递增内容版本（游戏服务器 30 秒内同步）。

### 模式设置（每个模式 `settings.antiCheat`，所有游戏类型都有，由后端 `GenericModeSettings` 规范化）

```json
"antiCheat": { "enabled": true, "preset": "standard", "toleranceScale": null, "checks": { "movement.velocity": { "enabled": false } } }
```

- `enabled` false = 该模式完全不检测。
- `preset` 缺省取游戏类型的默认预设：`BOMB` → `strict`，`WORLD`、`AIM_PRACTICE`、`BRIDGE_PRACTICE` → `relaxed`，其他 → `standard`（后端 `GameTypeDefinition.antiCheatPreset()`）。
- `toleranceScale` null = 用预设的；数字 0.1–5 覆盖预设。
- `checks`：同预设的 `checks`，叠加在预设之上。

### 解析顺序（模组 `RuleResolver`，按模式 id + 内容版本缓存）

`checks.json` 默认值 ← 预设 `checks` ← 模式 `checks`；预设级字段取自预设（模式只能覆盖 `toleranceScale`）。
预设不存在 → `defaultPreset` → 内置 `standard` 数值。全局 `enabled` false 或模式 `enabled` false → 不检测（状态仍然跟踪，以便打开时立即可用）。

## §5 延迟补偿

- **应答（transaction）**：服务端每 10 tick 给世界里的每名玩家发一个原版 `ClientboundPingPacket`（id 为负数，与原版不冲突）；本 tick 发出过击退 / 爆炸速度 / 传送的玩家在 tick 末额外发一个。客户端在主线程按顺序处理收到的包并立即回 `ServerboundPongPacket`，所以「收到 pong N」= 客户端已经执行了 ping N 之前的所有包。pong 在网络线程记下到达时间后，与移动包排进同一个主线程队列，保证与移动包的先后顺序。
- **往返时间**：pong 到达时间 − ping 发送时间（含客户端一帧）；`LatencyStats` 维护平滑值、抖动（与平滑值之差的平滑）与最近 10 次的最大值。
- **补偿窗口**：`windowTicks = ceil(min(maxCompensationMs, rtt + jitterMultiplier × jitter) / 50) + extraTicks`。服务端状态变化（速度属性、效果、疾跑、动作、降落伞、能力）在窗口内取「变化前后都合法」；击退 / 爆炸速度在应答之前视为「可能已生效也可能没有」，应答之后的第一次移动必须体现（`movement.velocity`）。
- **高延迟**：`rtt > highPingMs` 时，受延迟影响的检测（§3）的置信度乘 `max(0.3, highPingMs / rtt)`，且不自动封禁；回退照常执行（延迟不能成为作弊的掩护）。往返时间超过 `maxCompensationMs` 时窗口不再变大。
- **应答超时**：最早一个未应答的 ping 超过 `20 s × tolerance` 仍未应答而客户端仍在发其他包 → `packet.transaction`（拖住应答以拖延击退生效的作弊）。
- **计时器余额**（`TickClock`）：每个 `ServerboundClientTickEndPacket` 按**网络线程到达时间**计：`balance += 距上次到达的毫秒 − 50`，上限 `max(1500, 2 × 最近最大往返时间) × tolerance` 毫秒（网络卡顿后成批到达的包用卡顿期间攒下的余额抵消），低于 `−(120 + 2 × jitter) × tolerance` 毫秒时每多出 50 毫秒记 1 VL 并把余额拉回下限。进入世界、换维度、复活时清零。
- **移动包与客户端 tick**：两个移动包之间收到的 tick 结束包个数就是经过的客户端 tick 数；客户端在静止 tick 不发移动包，所以静止 tick 按「速度衰减、无位移」计；一个 tick 内出现两个移动包记 `packet.timer`。

## §6 物理预测

`MovePredictor.envelope(MoveInput)` 是纯函数（无 Minecraft 世界依赖，`tools/anticheat/check_movement.py` 离线测试）。按原版 1.21.8 `LivingEntity.travel` 的顺序：本 tick 位移 = 上 tick 存下的速度 + 本 tick 输入加速度；**摩擦在移动前按当时是否着地选定**，碰撞后乘它存为下 tick 的速度。

- **水平上界**：`raw = 上一步 × 摩擦(上一步开始时) + 加速度(本步开始时) + 疾跑起跳 0.2（本步开始时着地且本步确实起跳：上升或头顶有方块） + 服务端速度 + 实体推挤`，模组机制设定的速度按 `MotionBudget.horizontal + 加速度 + 起跳 0.2` 计（滑铲中起跳保留滑铲速度并获得疾跑起跳推力）；上限 `= raw × (1 + 0.01 × tol) + 0.01 × tol`。
  - 地面：摩擦 `= 方块滑度 × 0.91`，加速度 `= 速度属性 × 0.216 / 滑度³`（支撑候选取脚下中心、服务端的支撑方块与碰撞箱四角所在列的方块：原版按实际托住玩家的方块取滑度，站在边缘时可能是任意一个；加速度取最不滑的，继承速度取最滑的）；空中：摩擦 0.91，加速度 0.026。
  - 速度属性取补偿窗口内的最大值；服务端认为没在疾跑时再乘 1.3（客户端可能已开始疾跑）。潜行时上一步至少按 0.1 计（边缘回退时位移小于速度）。
  - 水中：摩擦 0.9（疾跑游泳）/ 0.96（海豚的恩惠），加速度 `0.02 + 水下速度加成`，再加水流 0.1；岩浆：0.5 / 0.02。
  - 有多个客户端 tick 未发移动包时，上一步按摩擦衰减。
  - **宽限不累积**：下一步继承的「上一步」封顶为本步的 `raw`（不含宽限），所以每 tick 多出一点的加速作弊不能把上限越抬越高（离线测试：地面 1.12 倍速、疾跑跳 1.15 倍速都会逐步被判）。
- **垂直上界**：本步开始时着地 → 跳跃速度 `0.42 + 0.1 × (跳跃提升等级 + 1)`；空中 → `(上一步竖直 − g) × 0.98`（`g` 为重力属性，缓降时下落阶段 0.01）；**本步结束时服务端判定站在方块上**（落地截断下落、或踩上边缘）→ 至少台阶高度；梯子 / 藤蔓 / 脚手架 0.2；液体 0.5；落到粘液块 / 床上反弹 `|下落速度|`（脚下四角任一是粘液块 / 床即算：原版按脚下支撑方块反弹）；贴着蜂蜜块侧面（碰撞箱伸进蜂蜜块格子、不高于其顶 1/16）下落至少可慢到 `HONEY_SLIDE = (−0.05 − 0.08) × 0.98`（原版每 tick 末把下落设成它）；站在 / 踩上实体平台时再加平台余量（见下）；漂浮、无重力、气泡柱、蜘蛛网 / 细雪 / 甜浆果丛直接豁免垂直检测；加上服务端速度的竖直分量，并至少为 `MotionBudget.up`（飞扑把上升设为「仍在上升的部分 + 抬升」）；宽限 `+ 0.02 × tol`。物理上的「着地」永远用服务端碰撞判定，与 `movement.ground` 的开关无关。
- **追踪**：`/bl ac trace <玩家>` 开关后，该玩家的每次违规（含未到回退阈值的）都写入服务端日志，并附最近 8 次移动（客户端 tick、水平 / 竖直位移、声称 / 服务端着地、起点是否着地、滑度、弹性地面、是否未判），用于定位误判。
- **悬停**：服务端判定在空中（脚下无碰撞、不在液体 / 梯子上）却连续 ≥ 3 个客户端 tick 没有移动包 → `movement.vertical`。
- **实体推挤**：水平超出上界时才查；可推动的实体（玩家、生物）碰撞箱按它在 `窗口 + 3` tick 内移动的距离加宽（至多 1.5 格）后与玩家本步的扫过范围相交即计入，每个加 0.08，最多 3 个（客户端按自己那份晚几 tick 的位置推挤）。
- **实体平台**（船、潜影贝、成年快乐恶魂的背）：客户端站在自己那份实体上，而移动中的实体客户端看到的是几 tick 前的位置（插值 3 tick + 延迟）。`Surroundings.platform` 找脚下的这类实体，碰撞箱按它在 `窗口 + 3` tick 内移动的距离加宽（静止 0.05，潜影贝开盖至少 0.55，封顶 0.6）；脚在它顶部的余量内即算站着（着地、悬停判定都认）。垂直上界在终点站上平台时允许台阶高度（原版从地面或落地时会一步踩上船的 0.5625），起点或终点挨着平台时再加这份余量。证据里列出附近平台实体的顶部高度与每 tick 移动量。
- **活塞**：Forge `PistonEvent.Post`（服务端）记下最近移动的活塞（`track/MovingBlocks`）；玩家在活塞 14 格内、移动发生在补偿窗口 + 4 tick 内的移动两轴都不判（被推动的方块带着玩家走、粘液块把人弹飞，客户端同样如此），之后的移动从被推到的位置继续按物理判。
- **着地判定**：声称着地时用玩家在新位置的碰撞箱向下扩 0.04 做方块 + 实体碰撞查询，再看实体平台；没有即 `movement.ground`，并且本包交给原版处理时着地状态取服务端判定（`@Redirect isOnGround`），所以无摔落作弊照样受摔落伤害。
- **击退（`movement.velocity`）**：从发出到客户端应答其后 ping 的下一次移动为止，记下各次移动沿速度方向走得最远的一次；它必须 ≥ `|V水平| × (1 − 0.35 × tol) − 1.5 × accel`（叠加型爆炸速度再减去玩家原有速度可能的反向分量），`V竖直 > 0.1` 的设定速度还要求上升 ≥ `V竖直 × (1 − 0.35 × tol)`；服务端按速度移动碰撞箱，会撞墙 / 撞顶的方向不检查；期间处于模组自己的运动（滑铲、飞扑、降落伞）或液体、梯子、蜘蛛网中的不检查。
- **豁免**：死亡、旁观、创造、能飞且在飞、乘坐载具、睡觉、鞘翅滑翔、等待原版传送确认（`awaitingPositionFromClient`）、刚传送 / 复活 / 换维度后的第一个移动包、`/bl ac exempt`。
- **`MotionAllowance`**（扩展点）：模块为自己的机制填 `MotionBudget`：`horizontal`（机制可能设定的水平速度）、`up`（机制可能给的最快上升）、`exemptHorizontal` / `exemptVertical`、`reason`。已有：滑铲 / 飞扑 / 匍匐（`server/movement/ActionAllowance`：动作进行中与结束后补偿窗口内）、降落伞（`server/parachute/ParachuteAllowance`：穿着降落伞在空中时豁免，由 `ParachuteFlightCheck` 负责并上报 `movement.parachute`）。

## §7 证据与置信度

- **VL**：每项检测一个，违规时增加，不违规时按 `decay`/秒减少（按时间戳惰性计算，不逐 tick 处理）。
- **单项置信度**：`c = weight × (1 − e^(−VL / s)) × 延迟系数`，`s = kickVl > 0 ? 0.35 × kickVl : 15`。
- **总置信度**：`C = 1 − Π(1 − c_i)`（独立证据合并）。
- **动作**（按预设阈值）：`C ≥ alert` → 提醒在线管理员（每人 `alerts.cooldownSeconds` 一次）；`C ≥ review` → 上报时标记 `review`；某项 `VL ≥ kickVl` → 踢出；`C ≥ autoBan` 且预设与全局都允许自动封禁、延迟不高、贡献最大的检测 `punish` 为真且其单项置信度 ≥ 0.5 → 请求后端封禁。
- **上报**：某项 VL 首次越过 `setbackVl`、此后每再增加 `max(1, setbackVl)`、或发生踢出 / 封禁时，生成一条证据；同一玩家同一检测每 `evidence.reportCooldownSeconds` 秒最多一条；每 5 秒批量 `POST` 一次（失败重试一次后丢弃并记日志）。

## §8 执行

- **回退**：传送回最后一次合法位置并清零速度（原版 `teleport`，之后等待客户端确认）。
- **取消**：攻击类检测在 Forge 事件里取消该次攻击；武器信号由武器模块自己丢弃。
- **踢出**：`blockland.anticheat.kick`（原因只写「检测到异常行为」与检测名，不泄露阈值）。
- **封禁**：模组 `POST /api/server/anticheat/punish`，后端决定并写入玩家封禁，模组随即踢出（`blockland.profile.banned` 同款提示）。
- **管理员提醒**：权限 ≥ 2 的在线玩家（可 `/bl ac alerts` 关闭自己的提醒），世界里是聊天、大厅里是提示。
- **命令**（权限 2）：`/bl ac status [玩家]`、`/bl ac alerts`、`/bl ac exempt <玩家> <秒>`、`/bl ac reset <玩家>`、`/bl ac stats`。

## §9 后端接口

### 游戏服务器（`X-Server-Token`）

`POST /api/server/registry/anticheat` —— 发布检测目录（即 `checks.json` 的 `checks` 转为数组）：

```json
{ "checks": [ { "id": "movement.speed", "category": "movement", "name": "移动速度", "description": "…", "latencySensitive": true,
                "signal": false, "defaults": { "enabled": true, "tolerance": 1.0, "setbackVl": 3, "kickVl": 60, "decay": 0.4, "weight": 0.7, "punish": true } } ] }
```

`POST /api/server/anticheat/reports` —— 证据批量上报，返回 `{ "accepted": n }`：

```json
{ "reports": [ {
    "uuid": "…", "name": "Steve", "modeId": "bomb_5v5", "modeTitle": "枪战爆破 5v5", "mapId": "bomb_demo",
    "check": "movement.speed", "category": "movement", "vl": 12.5, "confidence": 0.82, "review": true,
    "action": "setback", "detail": "水平 0.612 > 上限 0.338（地面，疾跑）",
    "ping": 85, "jitter": 12, "world": "blockland:arena", "x": 12.5, "y": 64.0, "z": -3.2,
    "at": "2026-10-25T08:00:00Z" } ] }
```

`action`：`none` | `setback` | `cancel` | `kick` | `ban`。`modeId` / `modeTitle` / `mapId` 不在对局时为 null。

`POST /api/server/anticheat/punish` —— 请求自动封禁，返回 `{ "banned": true, "caseId": "…", "message": "…" }`：

```json
{ "uuid": "…", "name": "Steve", "modeId": "bomb_5v5", "checks": ["movement.speed", "packet.timer"], "confidence": 0.985, "detail": "…" }
```

后端只有在全局 `autoBan.enabled` 时才封禁（`PlayerService.ban`，时长 `autoBan.durationMinutes`，原因 `<reason> #<案件编号>`），并把处罚写进案件；否则返回 `banned: false` 且把案件标为待复核。

### 案件（后端 `anticheat-cases.json`，运行数据，不递增内容版本）

```json
{
  "id": "k7f3…", "playerUuid": "…", "playerName": "Steve",
  "status": "OPEN",
  "openedAt": "…", "updatedAt": "…", "closedAt": null,
  "suspicion": 2.4, "maxConfidence": 0.86, "watched": false,
  "checks": { "movement.speed": { "count": 7, "maxVl": 14.0, "maxConfidence": 0.82, "lastAt": "…" } },
  "modes": { "bomb_5v5": 7 },
  "evidence": [ /* 最近 maxPerCase 条上报，旧的先删 */ ],
  "punishments": [ { "type": "BAN", "by": "auto", "minutes": 0, "reason": "…", "at": "…" } ],
  "notes": [ { "text": "…", "by": "admin", "at": "…" } ],
  "decision": null, "reviewedAt": null
}
```

- `status`：`OPEN`（收集中）→ `REVIEW`（待人工复核：`suspicion ≥ reviewScore`、上报带 `review`、或自动封禁请求被拒）→ `BANNED` / `CLEARED`（确认误判）/ `DISMISSED`（不予处理）。每名玩家同时最多一个 `OPEN`/`REVIEW` 案件；关闭后的新上报开新案件。
- `suspicion`：每条上报 `suspicion = suspicion × 0.5^(Δ天 / halfLifeDays) + confidence`。
- 超过 `retentionDays` 未更新的已关闭案件在启动时与每天清理。

### 管理后台（`Authorization: Bearer`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/anticheat/cases?status=ACTIVE\|OPEN\|REVIEW\|CLOSED\|ALL&q=` | 案件列表（不含 `evidence`，按 `status` REVIEW 优先、`updatedAt` 倒序） |
| GET | `/api/admin/anticheat/cases/{id}` | 案件详情 |
| POST | `/api/admin/anticheat/cases/{id}/review` | `{ "decision": "BAN"\|"CLEAR"\|"DISMISS"\|"WATCH", "minutes": 0, "reason": "…", "note": "…" }`；BAN 调用 `PlayerService.ban` |
| POST | `/api/admin/anticheat/cases/{id}/notes` | `{ "text": "…" }` |
| DELETE | `/api/admin/anticheat/cases/{id}` | 删除案件 |
| GET | `/api/admin/anticheat/players/{uuid}/cases` | 某玩家的全部案件（用户页链接） |
| GET | `/api/admin/anticheat/summary` | `{ "open": n, "review": n, "bannedLast7Days": n, "reportsLast24h": n }` |
| GET / PUT | `/api/admin/anticheat/config` | 全局配置（PUT 校验后保存，返回规范化结果） |
| GET | `/api/admin/registry/anticheat` | 检测目录 |

## §10 后台界面

- 侧栏「反作弊」：**案件**（筛选状态 / 搜索玩家，列表显示玩家、状态、可疑度、最高置信度、主要检测、最后时间；详情页显示各检测统计、证据时间线（检测、VL、置信度、处置、延迟、模式、坐标、细节）、处罚与备注，按钮：封禁（时长、原因）/ 确认误判 / 不予处理 / 继续观察 / 添加备注）与 **设置**（全局开关、默认预设、自动封禁、提醒、证据保留、可疑度；预设编辑：预设级字段 + 检测表格（每项 启用 / 宽限 / 回退 VL / 踢出 VL / 衰减 / 权重 / 可封禁，空白 = 继承默认））。
- 「小游戏管理」编辑模式：「反作弊」一栏（启用、预设下拉、宽限倍率、按检测覆盖的表格，空白 = 继承预设）。
- 用户页：玩家有案件时显示徽标并链接到其案件。

## §11 扩展

- **新检测**：在 `checks.json` 加条目，实现检测并在 `AntiCheatService` 注册；后台自动出现。
- **新的移动机制**（如钩爪、跳板）：模块实现 `MotionAllowance`，在 `BlockLandServer` 里 `antiCheat.addAllowance(...)`；不要改预测引擎。由服务端施加的速度（`setDeltaMovement` + `hurtMarked`、爆炸）自动被速度账本捕获，不需要声明。
- **新武器 / 战斗规则**：在武器模块拒绝请求的地方调用 `Suspicion.report(player, AntiCheatSignals.X, amount, detail)`，并在 `checks.json` 加一个 `signal: true` 的条目。
- **新游戏类型**：后端定义里覆盖 `antiCheatPreset()` 选默认预设；需要类型专属的规则时在 `checks.json` 新增检测，并由模式的 `checks` 覆盖开关。

## §12 测试

- `python -I tools/anticheat/check_movement.py`：离线编译 `predict/*` 与 `MovementCheck.java`，用原版公式模拟走路、疾跑、疾跑跳、冰面跳跑、下落、跳跃提升、速度效果、击退、多 tick 静止等合法序列（不得违规），粘液块 / 床反弹、蜂蜜块（半高跳跃、贴墙慢滑）、雪层逐层踩上、踩上 / 跳离位置有偏差的船，以及 1.12–1.3 倍速度、悬停、慢速滑翔（含贴着蜂蜜块）、高跳、台阶（含船旁）、超额反弹作弊（必须违规）。改了 `predict/` 必须跑。世界相关的部分（平台查找、反弹方块、蜂蜜块格子、活塞）在开发服上用自动化客户端 + RCON 验证。
- 后端：`mvn -q test`（预设与模式设置规范化、案件累积与状态流转、自动封禁开关）。
- mixin：按 AGENTS.md 用 Forge 自带 Mixin 0.8.7 离线套用全部配置。
- 实机（用户）：各模式正常移动、滑铲 / 飞扑 / 降落伞 / 大逃杀跳机 / 被枪击与爆炸击退 / 高延迟（`clumsy` 等工具加 300–800 ms）都不应被回退；用作弊客户端或 `/bl ac` 观察 VL。
