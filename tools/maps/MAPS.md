# 内置地图

后端自带的地图是固定文件，提交在仓库里：`server/backend/src/main/resources/maps/shipped/`。后端运行时**不生成地图**。

## 文件

每张地图三份文件，以地图 ID 命名：

| 文件 | 内容 |
| --- | --- |
| `<id>.nbt`（或 `.schem`） | 地图方块本身 |
| `<id>.json` | `id`、`name`、`gameType`、`voidY`（虚空判定高度）、`teamSpawns`（各队出生点）、`settings`（游戏类型的地图设置：包点、箱子、角色出生点、物资点、氛围等），可选 `spawnMarkerBlock` |
| `<id>.png` | 大厅地图选择里的封面（可选） |

坐标都相对地图最小角。格式与后台地图编辑器保存的元数据相同。

## 怎么装进后端

- **全新安装**：后端第一次启动时，各游戏类型的种子（`gametype/*/…Seed`）调用 `ShippedMaps.install(id)`，把文件、元数据、封面原样装进 `data/`。已存在同 ID 的地图不会覆盖。
- **已有安装**：种子按 ID 只运行一次（记在 `data/seeds.json`），所以更新仓库里的地图文件**不会**自动改动已经装好的地图。
- **要把改过的地图推到已有安装**：加一个新的种子（新的 `id()`，例如 `bomb-dust-a-v3`），在里面调用 `ShippedMaps.refresh(id)`。文件不同时会替换文件、出生点和类型设置；名称、虚空高度和氛围保留管理员的设置。
- `ShippedMapsTest` 会检查每张内置地图都能读取、没有悬空方块，并且全新安装后存进去的文件、元数据、封面与仓库里的完全一致。

## 怎么修改地图

### 在后台或游戏里改

在开发后端上改好（后台上传新的 `.nbt` / `.schem`、编辑出生点和设置、换封面），然后导出到仓库：

```bash
python -I tools/maps/export_maps.py dust_a
```

`--all` 导出数据目录里的全部地图，`--data <目录>` 指定其他后端的数据目录（默认 `server/backend/data`）。

### 用生成器改

一部分地图最初是代码生成的（`DustAArena`、`BombDemoArena`、`OriginalSkyWarsMaps` 等）。这些生成器现在只是工具，放在 `server/backend/src/test/java`（包名不变），配方集中在 `map/GeneratedMaps`。改完生成器后手动导出：

```powershell
mvn -f server/backend/pom.xml test -Dtest=GeneratedMapsTest "-Dblockland.exportGenerated=dust_a"
```

`all` 表示全部生成器。导出会写入 `.nbt`，用生成器的出生点和类型设置覆盖 `.json` 里对应的部分（名称、虚空高度、氛围等其他设置保留），并且只在没有封面时补上封面。

`GeneratedMapsTest` 平时会列出哪些内置地图与生成器的输出不一致（不算失败，因为地图可以手工改过）。目前只有 `demo_islands`：线上这份是旧版生成器生成的，之后生成器改过，但地图一直沿用旧文件。

### 新增一张内置地图

1. 把 `<id>.nbt`、`<id>.json`（可选 `<id>.png`）放进 `maps/shipped/`，可以用上面两种方式之一导出。
2. 在对应游戏类型的种子里调用 `shipped.install("<id>")`。种子是新加的，或者给它换一个新 `id()`，已有安装才会运行它。如果要加进模式的地图池，照 `DustASeed` 的写法处理。
