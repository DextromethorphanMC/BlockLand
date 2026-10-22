# BlockLand 开发规范（给 AI 代理与开发者）

## 架构

- **低耦合是硬性要求**：每个功能模块只依赖接口/数据，不依赖其他模块的内部实现。新增功能时先想清楚边界：
  - 模组服务端：服务之间通过接口、监听器或「已分好的数据」（如 `MatchRoster`）交互，由 `BlockLandServer` 统一组装；
  - 网络层只分发给 listener 接口，不引用服务端/客户端逻辑（枪械、刀具各有独立的 `GunPacketListeners` / `MeleePacketListeners`）；
  - 客户端界面拆成独立组件（`client/screen/lobby/*`、`client/gun/*`、`client/melee/*`），由屏幕类只负责布局与连线；
  - 武器共用的部分放在共享模块（`client/viewmodel/*`、`client/input/WeaponInput`、`client/hud/HitMarker`、`client/decal/Decals`），各武器模块之间互不引用；
  - 游戏类型、配置、美术资源都是「数据 + 约定」：枪械走 `guns.json`，刀具走 `knives.json`，损伤贴图走 `decals.json`，模型走 `tools/assets/VIEWMODEL_SPEC.md`，不要把具体内容写死进逻辑代码。
- 后端（`server/backend`）、管理后台（`server/web`）与模组之间只通过 HTTP 接口/JSON 约定通信。
- 新代码风格与周围代码保持一致（注释密度、命名、写法）。

## 测试

- **实机视觉相关的测试（游戏画面、模型/动画/特效的观感、手感、真实键鼠操作等）交给用户来做**，代理不要自行启动客户端做视觉验收，也不要因此打断用户的桌面操作。
- 代理负责非视觉的验证：编译、单元/脚本级检查（如 `GlbCheck`、动画库测试）、后端与管理后台构建、服务端逻辑与接口（可用 RCON、curl）。
- 需要用户实机验证时，在回复里清楚列出：改了什么、怎么启动、请重点看哪些现象。

## 开发环境提示

- 在本仓库内的构建、运行、测试命令可直接执行，无需逐条征求用户确认。
- Windows 下用 `mod/build.ps1`（会处理 JDK、代理与 Gradle 参数）；开发期测试驱动 `client/dev/DevAutomation` 仅在 `-Pautomation=<目录>` 时启用。
- 开发令牌 `change-me-admin` / `change-me-server` 仅限本地，上线前必须修改。
