# BlockLand 开发规范（给 AI 代理与开发者）

## 架构

- **低耦合是硬性要求**：每个功能模块只依赖接口/数据，不依赖其他模块的内部实现。新增功能时先想清楚边界：
  - 模组服务端：服务之间通过接口、监听器或「已分好的数据」（如 `MatchRoster`）交互，由 `BlockLandServer` 统一组装；
  - 网络层只分发给 listener 接口，不引用服务端/客户端逻辑（枪械、刀具各有独立的 `GunPacketListeners` / `MeleePacketListeners`）；
  - 客户端界面拆成独立组件（`client/screen/lobby/*`、`client/gun/*`、`client/melee/*`），由屏幕类只负责布局与连线；
  - 武器的命中判定统一问 `combat/HitShapes`（不要直接拿实体碰撞箱），射击/挥刀的起点用 `movement/Slides.eyePosition`，这样滑铲等姿态变化对所有武器自动生效；
  - 武器共用的部分放在共享模块（`client/viewmodel/*`、`client/input/WeaponInput`、`client/hud/HitMarker`、`client/decal/Decals`），各武器模块之间互不引用；
  - 游戏类型、配置、美术资源都是「数据 + 约定」：枪械走 `guns.json`，刀具走 `knives.json`，通用配件（瞄准镜、激光、枪管、枪托、挂件）走 `craft/attachments.json`、武器只声明槽位，感染怪物走 `monsters.json`，炸药包走 `bomb/bomb.json`（下包 / 拆包 / 引信时长是爆破模式的设置），墙面喷漆走 `graffiti/graffiti.json`（普通 / 特殊效果、免费与否；是否允许喷漆是模式设置 `allowGraffiti`），损伤贴图走 `decals.json`，子弹与刀刃命中的碎屑 / 粉尘 / 火花 / 血迹 / 水花走 `impacts.json`（武器只调用 `client/impact/Impacts`，传命中点、方向和弹种或攻击的 `impactProfile`），子弹在水和岩浆里的衰减与打水漂走共享的 `combat/Ballistics`（弹药在 `guns.json` 写 `waterRange` / `lavaRange` / `skipAngle`，服务端判定与客户端预测共用同一段代码），浸湿与滴水走 `client/render/Wetness`，滑铲走 `movement/slide.json`（是否允许是模式设置 `allowSlide`），材质走 GLB 材质 `extras`（`pbr` 纹理组 + `region` 区域），皮肤按区域写（武器自己的 `skins` 与共享的 `craft/skins.json`），特殊皮肤的专属检视与击杀反馈写在皮肤的 `inspect` / `fx.kill`（片段与事件提示、`surge` 动态材质、粒子预设 `viewmodel/particles.json`，由 `client/viewmodel/SkinEffects` 播放），瞄准镜走配件的 `scope` 对象，模型（含配件挂点）走 `tools/assets/VIEWMODEL_SPEC.md`，不要把具体内容写死进逻辑代码。
- **瞄具统一走光学模块**：武器只向 `client/scope/Optics` 提供本帧的 `SightPose`（瞄具节点位置），开镜视场、灵敏度、晃动、射击方向（瞄准线）都问 `Optics`；倍镜的第二次世界渲染必须与主画面隔离——不要把视锥交给原版当「捕获的调试视锥」画出来、不要重写主画面本帧在用的 uniform 槽（GPU 缓冲是无同步持久映射），新增需要对镜内隐藏的东西时在 `ScopePicture` 里处理（`VIEWMODEL_SPEC.md` §22）。
- **渲染管线走 `client/graphics`，契约见 `tools/assets/RENDERING_SPEC.md`**：画面阶段（`FrameStage`）、渲染分辨率与抖动、后期链、阴影、世界光照各自独立；功能模块只通过 `client/render` 的契约使用它（`SceneLighting` 取场景光照、`LightSources` 加动态光源、`FrameJitter` 给自己的投影加抖动、`ShadowPass` 判断阴影捕获、`HandMotion` 取第一人称运动矢量目标——自己画的第一人称几何用 `DrawList.motion` 写入运动矢量，TAA / DLSS / 动态模糊才能正确处理），不要引用 `client/graphics` 内部。原版着色器只能在内置资源包 `resourcepacks/blockland_lighting` 里覆盖，未打补丁（`BlRole` 0）或光照关闭时必须与原版逐像素一致；块材质（自发光、水、金属）与手持光源是数据（`graphics/block_materials.json`、`item_lights.json`）。
- **我的世界是引擎，不兼容其他渲染模组**：不做 OptiFine / 光影包的兼容与回退，自定义管线可以假定画面由 BlockLand 自己渲染；原版渲染类型只用于阴影图的实体捕获（`client/render/ShadowPass` 期间第三人称武器走 `VanillaDraws`）。
- **界面文字用模组字体**：界面与 HUD 文字通过 `client/font/UiFont`（或 `client/ui/Draw.text/fit/wrap`）绘制和测量，不要直接用原版 `Font`；语言文件新增汉字后运行 `python tools/fonts/subset_fonts.py --check`，缺字时重新生成字体子集。
- **大厅是连接的配置阶段**：大厅里没有世界，服务端没有 `ServerPlayer`，客户端没有 `mc.player` / `mc.level`。
  - 大厅相关的服务端代码按玩家 UUID 工作，通过 `server/session/PlayerDirectory` 找人、发提示（大厅里是 toast，世界里是聊天）；对局通过 `server/game/PlayerTransit.bring` 把玩家带进世界、`toLobby` 送回。Forge 的登录/登出事件只表示「进入/离开世界」。
  - 大厅里会收发的包用 `BLNetwork.anyToServer` / `anyToClient` 注册，只能用不依赖注册表的编码（物品用 `S2CKitData.ItemView` 这样的展示数据，文字用 `TextJson`）。
  - 客户端大厅界面不能依赖玩家实体或世界（自己的账号用 `ClientLobbyState.selfId()` / `selfName()`）。
- **界面文字走语言文件**：玩家能看到的文字都放进 `assets/blockland/lang/zh_cn.json` 和 `en_us.json`（客户端用 `client/ui/Tr.tr`，服务端发送 `Component.translatable`），不要在代码里写死中文；后台录入的内容（模式名、地图名、公告）原样显示。
- 后端（`server/backend`）、管理后台（`server/web`）与模组之间只通过 HTTP 接口/JSON 约定通信。
- 新代码风格与周围代码保持一致（注释密度、命名、写法）。

## 测试

- **实机视觉相关的测试（游戏画面、模型/动画/特效的观感、手感、真实键鼠操作等）交给用户来做**，代理不要自行启动客户端做视觉验收，也不要因此打断用户的桌面操作。
- 代理负责非视觉的验证：编译、单元/脚本级检查（如 `GlbCheck`、动画库测试）、后端与管理后台构建、服务端逻辑与接口（可用 RCON、curl）。
- 改了 DLSS 桥接（`client/graphics/upscale/dlss`）或其输入着色器要跑 `python -I tools/dlss/check_dlss.py <运行库目录>`（隐藏窗口、真实 DLSS，比较各约定的误差）；改了帧生成（`FrameGeneration` / `FrameGenerator`）再跑 `--framegen`（不抢焦点，DLSS-G 接受全部输入且状态为 0 即通过；真正生成帧需要窗口在前台，交给用户实机看）；改了 DLSS 5 神经渲染（`Ngx` / `NeuralRendering` / `NeuralRenderer`）跑 `--neural`；改了光线重建跑 `--rr`；改了光线追踪的体素布局或 `rt_common.glsl` 跑 `python -I tools/raytrace/check_voxels.py`（GPU 追踪与 CPU 对照）；NVIDIA 的运行库不进仓库，用 `tools/dlss/install_runtime.py` 装到游戏目录。
- 改了 `combat/Ballistics`（子弹在流体中的弹道）要跑 `python -I tools/combat/check_ballistics.py`（只让 Gradle 给出编译类路径，不编译到 `mod/build`，开着开发客户端也能跑）。
- 改了任何着色器（含原版覆盖）都要跑 `python -I tools/shaders/check_shaders.py`（隐藏窗口里用本机驱动编译链接，不抢焦点），新的程序 / 宏组合登记到 `tools/shaders/programs.txt`。
- 需要用户实机验证时，在回复里清楚列出：改了什么、怎么启动、请重点看哪些现象。

## 开发环境提示

- 在本仓库内的构建、运行、测试命令可直接执行，无需逐条征求用户确认。
- Windows 下用 `mod/build.ps1`（会处理 JDK、代理与 Gradle 参数）；开发期测试驱动 `client/dev/DevAutomation` 仅在 `-Pautomation=<目录>` 时启用。
- 开发令牌 `change-me-admin` / `change-me-server` 仅限本地，上线前必须修改。
