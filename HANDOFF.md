# HubSuite 交接文档

> 给接手这个项目的 agent。你在 **MC+ 模式**下工作，所以你能用 `mc_*` 工具**真的进服务器**——
> 这正是上一轮做不到的事。请优先把下面「三、待办」里的两项实地验证掉。

工作目录：`E:\Github\ServerMod`

---

## 一、项目是什么

**HubSuite** —— MC **26.1.2** 的**纯服务端** Fabric 模组（Java 25），把**一个服务端**变成
「大厅 + 多个互相独立的子服」（生存 / 创造 / 空岛）。

核心设计：

- 每个子服 / 维度是**一个独立存档**（`IsolatedSave`，各一份 `LevelStorageAccess`）
- 每存档有**独立玩家数据**（背包/经验/成就/统计都按子服隔离）
- 三个子服 + 大厅 + 每个子服的**独立下界/末地**
- `h2` / LuckPerms / HuskHomes / Placeholder API 都是**软依赖**（运行时探测）

| 项 | 值 |
|---|---|
| 模组 id | `hubsuite`（组 `cn.dreamgary`） |
| 版本 | `gradle.properties` 里的 `mod_version` = **0.2.4** |
| 构建 | Gradle 9.7.1 + Loom 1.18-SNAPSHOT，官方 Mojang 名（**不 remap**） |
| 产物 | `build/libs/hubsuite-<版本>.jar` |

---

## 二、当前状态（接手时的事实）

```
main = 81d74a7  与 origin/main 同步，工作区干净
tag  = v0.2.4（正式）+ v0.2.4-rc.1（预发布）
本机自检 = 192 项通过 / 0 失败 / 看门狗 0 次（冷档冷启动）
```

release 列表：[v0.2.4 Latest](https://github.com/DRheEheAMGary/ServerMod/releases/tag/v0.2.4) ·
[v0.2.4-rc.1 预发布](https://github.com/DRheEheAMGary/ServerMod/releases/tag/v0.2.4-rc.1)

### 本机已验证过的（有自检证据）

| 问题 | 证据（自检输出） |
|---|---|
| 生存/创造是同一张地图 | `地形不同：6/6 个采样点高度不一致` |
| 虚空里长出村庄 | `虚空维度里没有任何结构集（4 个）` |
| 传送门**点不着** | `火能在本模组的维度里点燃传送门（4 个维度）` |
| 退出服务器**丢成就** | `重新登录，成就仍在：'minecraft:nether/obtain_crying_obsidian'` |
| 切服**不隔离**成就 | `切服隔离成立：生存服的成就没出现在创造服` |
| 缓存不放掉 | `切服后成就/统计缓存已放掉` |
| 传送门**改道** | `进/出各方向都指向本子服自己的维度（8 项映射）` |

### 未验证 —— **这就是你的主要工作**

1. **传送门端到端**：自检只验了"能点火"和"改道映射"，**没有真的让玩家钻进去**。
2. **生存服出生点是否还卡地里**：修了两处复检漏洞，但没在真实登录里复现过。
3. **下界/末地的地形**：生成器参数取自原版注册表，没实地看过。

---

## 三、待办（按优先级）

### ① 传送门端到端验证（最重要）

```
在生存服 spawn 附近堆一个黑曜石框架（4×5 中间空）→ 打火石点火
→ 确认生成了传送门方块（紫色）
→ 走进去 → 确认维度变成 hubsuite:survival_nether
→ 记下坐标 → 原路走回传送门 → 确认回到 hubsuite:server_survival
→ 再验证「创造服的玩家不可能串到生存服的下界」
```

失败时怎么查：`PortalLinks.redirect`（映射）+ `PortalBlockDestinationMixin`
（改 `getPortalDestination` 的返回值）+ `BaseFireBlockPortalMixin`（能不能点火）。
三者缺一不可。

### ② 生存服出生点

```
进服看脚下有没有卡在方块里 → 记坐标
/kill 后重生再看一次
```

失败时看 `SubServer.tickPendingSpawns`（延迟复检）与 `sanitizeSpawn`。

### ③（可选）把上面两条写成自检

自检比手测更耐久。要点：
- 点火可用 `BaseFireBlock` 的反射调用或直接调 `PortalShape` 类逻辑
- 传送可用假玩家（`FakePlayers.spawn`）+ 真实 `getPortalDestination` + 真实传送
- **别做同步重活**（见下面「五、坑」）

---

## 四、怎么跑起来

### 起测试服务端

**绝对不要用 25565** —— 那是用户自己客户端的端口，抢了会把人踢下线。用 **25566**。

```powershell
$j = "$env:USERPROFILE\scoop\apps\openjdk25\current"   # 必须 Java 25
$env:JAVA_HOME = $j; $env:PATH = "$j\bin;$env:PATH"
Set-Location E:\Github\ServerMod

# 无人值守自检（20 秒后开始）
.\gradlew.bat runServer --console=plain '-Dhubsuite.selftest=20' '-Pport=25566'
```

服务端是 **`online-mode=false`**，所以你（机器人）能直接进。
结果看 `run\logs\latest.log` 里的 `通过 N 项，失败 M 项`。

### 清档（要重新生成地形时必须做）

```powershell
foreach ($t in @("run\world","run\hubsuite_lobby","run\hubsuite_survival","run\hubsuite_creative",
                 "run\hubsuite_survival_nether","run\hubsuite_survival_end",
                 "run\hubsuite_creative_nether","run\hubsuite_creative_end",
                 "run\hubsuite_skyblock","run\hubsuite_skyblock_hub",
                 "run\hubsuite_skyblock_classic","run\hubsuite_skyblock_ocean",
                 "run\config","run\logs","run\crash-reports")) {
  if (Test-Path $t) { Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue }
}
Get-ChildItem run -Recurse -Filter "session.lock" -EA SilentlyContinue | Remove-Item -Force
# run\mods 和 run\server.properties 要留着
```

### 改完代码

```powershell
.\gradlew.bat build --console=plain     # 产物在 build\libs\
```

发布：改 `gradle.properties` 的 `mod_version` → 提交 → 打 tag → `gh release create`。
**注意 `gh --jq` 在 PowerShell 5.1 下引号会坏**，改成 dump JSON 再 `ConvertFrom-Json`。

---

## 五、坑（都是踩过的，别重踩）

### 环境

| 坑 | 说明 |
|---|---|
| **`pwsh` 不存在** | 只有 Windows PowerShell 5.1。后台任务用 `& script.ps1` |
| **`.ps1` 必须带 UTF-8 BOM** | 否则 PS 5.1 按 GBK 读，中文全是乱码 |
| `gh --jq` 引号易坏 | dump JSON 到文件再 `ConvertFrom-Json` |
| `git log --format=%B` 管道压平换行 | 提交信息写文件后用 `git commit -F` |
| heredoc `@"..."@` 吃变量 | 先写脚本文件再执行 |
| `"$sub\players"` 被当盘符 | 用 `Join-Path` |

### 代码（重要）

| 坑 | 说明 |
|---|---|
| **不要用 `hasChunk` 判断区块就绪** | 它只保证"非空"，不代表到 FULL。用 `getChunkNow(cx,cz) != null` |
| **绝不同步加载区块** | 主线程会卡到看门狗强杀（`A single server tick took 60.00 seconds`）。要等就 `defer(...)` 跨 tick |
| **自检整体跑在 `END_SERVER_TICK` 里（同步）** | 任何一步做重活都会超 60 秒被杀。落盘要传 `flush=false` 再用 defer 校验 |
| **成就/统计的路径烧死在 final 字段里** | `ServerStatsCounter.file` / `PlayerAdvancements.playerSavePath`。**缓存不清就永远写回旧文件** |
| **维度种子必须赶在 `new ServerLevel` 之前登记** | `ChunkMap` 在构造**内部**就取走种子了（见 `LevelSeeds.registerPending`） |
| **三个子服不能都用 `minecraft:overworld`** | `MinecraftServer.levels` 按 key 唯一。所以用 `hubsuite:server_<id>` 自有 key |
| **原版只认 `overworld`/`the_nether` 两个 key** | `BaseFireBlock.inPortalDimension` 写死的，所以自有维度点不着火 → 需要 mixin |
| **`Optional.empty()` 表示"没有结构覆盖"，会回落到群系自带结构集** | 要真关掉得给 `Optional.of(HolderSet.<StructureSet>empty())` |

### 排查手法（好用的）

- 崩溃栈：`run\crash-reports\`
- 自检进度：日志里搜 `[自检] ->`
- **字节码验证**：`javap -p -c -classpath <loom 的 minecraft jar>`
  jar 在 `~\.gradle\caches\fabric-loom\26.1.2\minecraft-extracted_server.jar`
  —— 比猜快得多，上面好几个根因都是这么定的

---

## 六、关键代码地图

| 文件 | 作用 |
|---|---|
| `world/WorldsManager.java` | 引擎入口：加载大厅 + 各子服、每 tick 泵（待铺平台/出生点补算） |
| `world/SubServer.java` | 子服/维度：建维度、出生点解析（`resolveSpawn`/`sanitizeSpawn`）、待铺平台 |
| `world/Lobby.java` | 大厅 |
| `world/IsolatedSave.java` | 独立存档（一份 `LevelStorageAccess` + 独立 `level.dat`） |
| `world/WorldBuilder.java` | 建 `ServerLevel`（**种子登记点**） |
| `world/WorldFactory.java` | 维度定义：normal / flat / **void（纯虚空，无结构）** / 原版下界末地 |
| `world/LevelSeeds.java` + `mixin/ServerLevelSeedMixin.java` | 每个维度自己的地形种子 |
| `world/PortalLinks.java` + `mixin/PortalBlockDestinationMixin.java` | 传送门去哪个维度 |
| `mixin/BaseFireBlockPortalMixin.java` | 让火能在自有维度点火 |
| `world/AuxDataRouter.java` + `mixin/PlayerListAuxDataMixin.java` | 成就/统计按子服隔离 |
| `world/PlayerDataRouter.java` + `mixin/PlayerListStorageMixin.java` | 背包/经验按子服隔离 |
| `world/PlayerRouter.java` | 切服主流程（`sendTo`） |
| `island/IslandService.java` + `island/IslandManager.java` | 空岛：建岛、归属、保护、出生点 |
| `world/SelfTest.java` | **全部自检**（192 项）。加检查看这里 |
| `HANDOFF.md` | 本文件 |

---

## 七、数据落盘位置（排查用）

**`config/hubsuite/`**（全局配置，跨子服共享）

| 文件 | 内容 |
|---|---|
| `accounts.db` | 账号库（SQLite） |
| `config.json` | 主配置（子服、种子、`ownNether`/`ownEnd`…） |
| `last-locations.json` | **坐标记忆** `玩家UUID → {维度key: [x,y,z,yaw,pitch]}` |
| `rules/<子服>.json` | 各子服 gamerule 快照 |
| `skyblock/islands-classic.json` / `islands-ocean.json` | **岛屿归属**（键是玩家 UUID） |
| `skyblock/quests.json` | 任务进度 |

**`run/hubsuite_<子服>/`**（每子服一份独立存档）

| 路径 | 内容 |
|---|---|
| `players/data/<uuid>.dat` | 背包 / 经验 / 血量 |
| `players/data/advancements/<uuid>.json` | **成就**（按子服隔离） |
| `players/data/stats/<uuid>.json` | **统计**（按子服隔离） |
| `level.dat` | 这个世界的种子与设置 |
| `dimensions/hubsuite/<维度>/region/` | 区块 |
| `dimensions/hubsuite/<维度>/entities/` | 实体 |

> 成就的**快速自检法**：在生存服拿个成就，看
> `run/hubsuite_survival/players/data/advancements/<uuid>.json` 是否出现、
> 同时 `run/world/players/data/advancements/` **不应该**增长。

---

## 八、用户的沟通偏好（重要）

- **说中文**
- **不要写那么多日志**：调试用的日志和自测，调试完就该删；别留刷屏的 INFO
- 用户会**亲手实测**并反馈，所以：
  - **不要声称验证过没验证的东西**。没验就明说"未验证"
  - 报告里给出**可复现的证据**（自检输出原文、日志行），不要只给结论
- 用户对**过度自信**很敏感，也很有耐心 —— 老实说"我这次没修对"比圆场好
- 改动**必须跑自检**：`-Dhubsuite.selftest=20`，确认 `失败 0 项` 且看门狗 0 次
- 用户会**当场纠正方向**（例如"不能换成 void 群系，不然没生物生成了"）——
  这类纠正通常是对的，先照做再验证

---

## 九、升级/测试提醒

改地形种子、虚空结构之后，**必须删存档**才看得到效果：

```
run/hubsuite_survival          run/hubsuite_skyblock_hub
run/hubsuite_creative          run/hubsuite_skyblock_classic
run/hubsuite_skyblock_ocean
```

清数据时**必须连地图存档一起删**。只清玩家数据而留着地图，会让旧地形和新地形叠在一起 ——
实测出现过「箱子被挤爆」「岛中间有旧树干」。
