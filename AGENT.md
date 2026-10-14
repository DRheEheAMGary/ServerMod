# AGENT.md — 给 AI Agent 的工作约定

> 本文件是**长期有效的工作约定 + 项目现状**：怎么搭环境、怎么改、怎么验、什么不能做，
> 以及关键代码地图、踩坑记录与未验证清单。
>
> 项目由人类作者与 AI Agent 协作开发，详见 [README.md](README.md) 的「AI 参与声明」。

---

## 一、动手前必读

1. **先读 git 日志**（`git log`）—— 当前版本、已验证 / 未验证清单与待办都记录在提交历史里。
   动手前把本文件的「七、环境坑」「八、排查手法」「十、Mixin 约定」看一遍，很多坑踩一次要花几小时。
2. **说话用中文**（作者是中文用户）。
3. **不要声称验证过没验证的东西**。没验就明说「未验证」。这是本项目最重要的一条约定 ——
   作者会亲手实测，虚报会被当场抓到，而且会浪费他更多时间。

---

## 二、项目速览

**HubSuite** —— Minecraft **26.1.2** 的**纯服务端** Fabric 模组（Java 25），
把一个服务端变成「大厅 + 多个互相独立的子服」（生存 / 创造 / 空岛）。

| 项 | 值 |
|---|---|
| 模组 id / 组 | `hubsuite` / `cn.dreamgary` |
| 版本 | `gradle.properties` 的 `mod_version` = **0.4.0-SNAPSHOT** |
| 构建 | Gradle 9.7.1 + Loom 1.18-SNAPSHOT |
| 映射 | **官方 Mojang 名，不 remap**（26.1 起官方不再混淆；build.gradle 无 mappings、无 remapJar） |
| 产物 | `build/libs/hubsuite-<版本>.jar`（另有 `-sources.jar`） |
| 规模 | 112 个 Java 文件 / 27 个 Mixin / 自检 232 项 |

### 架构要点（改之前必须理解）

- 每个子服 / 维度是**一个独立存档**（`IsolatedSave`，各一份 `LevelStorageAccess`）
- 每个存档有**独立玩家数据**：背包/经验/末影箱（`playerIo`）、成就、统计，三份都要隔离
- 所有自定义维度用 **`hubsuite:` 自有 key**，不占用 `minecraft:overworld` 等原版 key
- 软依赖（运行时探测，缺失要能降级）：LuckPerms / HuskHomes / Placeholder API / fabric-permissions-api / JEI
  - **JEI 服务端没有可调用的 API**：它的插件接口（IModPlugin）只在客户端加载，所以 JeiIntegration
    只做探测 + 记录，不引用 JEI 的类；客户端 JEI 的配方页数据走原版配方同步（我们的配方全是
    shaped/shapeless，物品由 Polymer 改写 SlotDisplay/Ingredient），配方转移由服务端 JEI 通用处理
- **硬依赖：Polymer**（自定义物品必须靠它映射给原版客户端；缺了 Fabric Loader 直接拒绝加载本模组）。
  build.gradle 里 `compileOnly polymer-core` + `runtimeOnly polymer-bundled`（后者只给 dev 运行时用）
- **维度只能启动时建立**：`MinecraftServer.levels` 是只读映射，运行期无法新建/卸载 → 增删子服必须重启

---

## 三、构建与运行

### 编译（必须 Java 25）

```bash
# macOS / Linux
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew build --console=plain
```

```powershell
# Windows（作者机器）
$j = "$env:USERPROFILE\scoop\apps\openjdk25\current"
$env:JAVA_HOME = $j; $env:PATH = "$j\bin;$env:PATH"
.\gradlew.bat build --console=plain
```

> ⚠️ 构建必须能读写 `~/.gradle`。文件沙箱只给 workspace 写权限时会失败在
> wrapper 锁文件上（`FileNotFoundException ... .zip.lck (Operation not permitted)`）。
> 另外 `maven.fabricmc.net` 很慢，Loom 首次拉取可能 `Read timed out`；可重试，
> 或用 `curl -C -` 断点续传补齐缓存（缓存路径见「九」）。

### 起测试服务端

> 🔴 **端口只能改 `run/server.properties` 文件 —— `-Pport=` 是无效参数！**
> `build.gradle` 只把 `-Dhubsuite.selftest` 接线到 `runServer` 的分叉 JVM。
> 传 `-Pport=25566` 它照样按 `server.properties` 起。**不要用 25565**（那是作者客户端的端口）。

```bash
# ① 改端口（示例 25566）
#    macOS：sed -i '' 's/^server-port=.*/server-port=25566/' run/server.properties
#    Windows：(Get-Content run\server.properties) -replace '^server-port=.*','server-port=25566' | Set-Content run\server.properties -Encoding ASCII

# ② 起服务端并无人值守自检（20 秒后开始）
./gradlew runServer --console=plain '-Dhubsuite.selftest=20'

# ③ 用完还原端口
```

- 服务端是 `online-mode=false`，机器人可直连。
- 自检结果看 `run/logs/latest.log` 里的 `通过 232 项，失败 0 项`。
- 控制台命令：`hub selftest`（也可在游戏内 `/hub selftest`）。

### 清档（改了地形 / 生成器才需要）

```bash
# 停服后
rm -rf run/hubsuite_* run/world run/config/hubsuite
rm -rf run/config/huskhomes run/config/luckperms
rm -f run/*/session.lock run/usercache.json run/ops.json
# run/mods 与 run/server.properties 要留着
```

> **清数据 = 连地图一起清**。只清玩家数据而留着地图，会让旧地形和新地形叠在一起 ——
> 实测出现过「箱子被挤爆」「岛中间有旧树干」。
> 强杀服务端后留下的 `session.lock` 不删，下次启动会报存档被锁。

---

## 四、改完代码的验收标准（硬性）

1. `./gradlew build --console=plain` **通过**
2. 跑一次自检：`./gradlew runServer --console=plain '-Dhubsuite.selftest=20'`
3. 日志里 **`失败 0 项`** 且 **看门狗 0 次**（搜 `A single server tick`）
4. 涉及多世界隔离 / 维度 / 玩家数据的改动，**优先补一条自检**而不是只靠手测

> 多世界隔离这类东西**光看代码确认不了**。本项目历史上好几个「看起来做对了、
> 实际根本没生效」的 bug（成就隔离、地形种子、虚空村庄）都是自检抓出来的。

### 加自检时的注意事项（整体跑在 `END_SERVER_TICK` 里，同步）

- ❌ 不要同步加载区块、不要大范围扫描、不要同步落盘 → 单 tick 超 60 秒会被看门狗强杀
- ✅ 要等世界生成 → 用 `defer(...)` 跨 tick 机制
- ✅ 要读方块 → 先用 `getChunkNow(cx,cz) != null` 确认区块到 FULL（**不要用 `hasChunk`**）
- ✅ 要落盘 → 传 `flush=false`，再用 `defer` 校验文件

---

## 五、编码约定

- **注释写「为什么」，不写「是什么」**。踩过的坑一定要留在注释里（本项目的注释密度就是这么来的，别删）
- **禁止阻塞主线程**。要等异步结果就跨 tick 延迟，不要 `.join()` / `.get()` / `Thread.sleep`
- **原版行为不能改坏**。改动原版逻辑时：没登记过的维度 / 没匹配的情况 → **原样返回原版结果**；
  加自检时顺便断言「原版行为保持不变」
- **少写日志**（作者明确要求过）：
  - 每次动作都触发的（建岛、补铺平台、切服）用 **debug**，不要 INFO
  - 调试用的临时日志和一次性排查方法，**调试完就删**
  - 启动时一次性、或异常路径的日志保留 INFO/WARN
- **加子服 / 岛型 / 任务不写代码** —— 那是配置驱动的，别硬编码
- **指令必须在模组初始化阶段注册**（`CommandRegistrationCallback` 在服务端启动前就触发完；
  放 `SERVER_STARTED` 里注册的指令进不了 dispatcher —— 实测踩过）
- **0.x 阶段不做数据迁移（重要）**：现在不是正式版，**一律不写迁移 / 兼容代码**。
  配置字段改了就直接改、存档格式不兼容就让用户删档、旧数据（家 / 岛屿 / 账号 / 成就）
  一律不管 —— 在文档和 release 里写清「要删档」即可。
  别为了「老存档还能用」加一堆分支：那是 1.0 以后才需要考虑的事。
- **关服两阶段**：`SERVER_STOPPING` 只主动落盘，`SERVER_STOPPED` 才清路由 / 关存档
  （清理全放 STOPPING 会让随后的 `playerList.saveAll()` 把在线玩家最终状态写进主世界）

---

## 六、环境坑

| 坑 | 说明 |
|---|---|
| **`-Pport=` 无效** | 见「三」。端口改 `run/server.properties`；**绝不要占 25565** |
| **`server.properties` 不能有非 ASCII** | Java Properties 按 ISO-8859-1 读，中文注释会 `MalformedInputException` |
| **强杀后要删 `session.lock`** | 否则下次启动报存档被锁 |
| **文档目录里的 `.md` 是本轮重写的** | 旧文档有大量过期内容（命令表、版本号），以本文件与源码为准 |
| `gh --jq` 引号易坏 | dump JSON 到文件再解析 |
| **HuskHomes 必须 4.11+26.1.2** | 旧版用废弃 intermediary 名，26.1.2 上 `NoClassDefFoundError`，版本检查拦不住 |

---

## 七、排查手法（本项目的「高杠杆」技巧）

### 字节码验证（最有用的一招）

不要靠猜原版行为，直接看字节码：

```bash
javap -p -c -classpath ~/.gradle/caches/fabric-loom/26.1.2/minecraft-extracted_server.jar \
  net.minecraft.server.level.ServerPlayer
```

本项目好几个根因都是这么定的（`ChunkMap` 在构造内部取种子、`PlayerList.remove` 不清缓存、
`BaseFireBlock` 写死只认两个维度 key、`findRespawnDimension` 读全局 `overworldData()`）。

### 日志 / 崩溃

```bash
grep "(HubSuite)" run/logs/latest.log | tail -50   # 日志前缀统一 (HubSuite)
grep -c Watchdog run/logs/latest.log              # 期望 0
ls -t run/crash-reports/*.txt | head -1           # 崩溃报告；搜 hubsuite 找非关服帧
grep "\[自检\] ->" run/logs/latest.log            # 自检进度
```

### 游戏内方块探测

- `data get block` **只对方块实体有效**（普通方块报 `not a block entity`）。普通方块用侧信道：

```mcfunction
scoreboard players set #m hsprobe 0
execute if block <x> <y> <z> <id> run scoreboard players set #m hsprobe 1
scoreboard players get #m hsprobe     # "#m has 1" 即命中
```

- 未加载区块里一切方块指令都返回 `That position is not loaded`，先 `forceload add`
  （**但不要在海洋等重维度 forceload**，会同步加载区块触发看门狗）。
- `data get entity <玩家> Pos` 在跨维度传输途中会整行失败，要重试。

### 区块就绪的正确判据

```java
// ✅ 不阻塞，没到 FULL 返回 null
if (level.getChunkSource().getChunkNow(cx, cz) == null) { /* 登记待办，下一 tick 重试 */ }
// ❌ hasChunk 只保证「非空」，getBlockState/setBlockAndUpdate 要 FULL，会同步等下去
```

---

## 八、关键代码地图

| 文件 | 作用 |
|---|---|
| `world/WorldsManager.java` | 引擎入口：加载大厅 + 各子服、每 tick 泵（待铺平台 / 出生点补算） |
| `world/SubServer.java` | 子服 / 维度：建维度、出生点解析（`resolveSpawn`/`sanitizeSpawn`）、待铺平台 |
| `world/PlayableWorld.java` | 场所抽象（大厅与子服同一接口） |
| `world/Lobby.java` | 大厅 |
| `world/IsolatedSave.java` | 独立存档（一份 `LevelStorageAccess` + 独立 `level.dat` + 玩家数据目录） |
| `world/WorldBuilder.java` | 建 `ServerLevel`（**种子登记点**） |
| `world/WorldFactory.java` | 维度定义：normal / flat / void / 原版下界末地 |
| `world/LevelSeeds.java` + `mixin/ServerLevelSeedMixin.java` | 每个维度自己的地形种子 |
| `world/PortalLinks.java` + `mixin/PortalBlockDestinationMixin.java` + `mixin/BaseFireBlockPortalMixin.java` | 传送门去哪个维度、**在那个维度里找门 / 建门**、自有维度能点火 |
| `mixin/ServerPlayerRespawnDimensionMixin.java` | 修正死亡重生的**维度与落点** |
| `world/AuxDataRouter.java` + `mixin/PlayerListAuxDataMixin.java` | 成就 / 统计按子服隔离（含反射替换 `ServerPlayer` 的 final 引用） |
| `world/PlayerListAuxAccess.java` | 上面那个 Mixin 对外暴露的操作接口 |
| `world/RespawnPoints.java` | 把玩家重生点绑到「当前所在子服」 |
| `world/PlayerDataRouter.java` + `world/PlayerStateStash.java` + `mixin/PlayerListStorageMixin.java` | 背包 / 经验按子服隔离（内存暂存 + 磁盘两份） |
| `world/PlayerRouter.java` | 切服主流程（`sendTo`） |
| `world/ServerInternals.java` / `ServerLevelsAccess.java` / `PlayerListAccess.java` | 访问原版内部字段的唯一出口（放业务包，见「十」） |
| `world/SelfTest.java` | **全部自检**（232 项）。加检查看这里 |
| `island/IslandService.java` + `island/IslandManager.java` | 空岛：建岛、归属、出生点（**保护已删**） |
| `world/IslandSideWorlds.java` + `world/LavaSeaGenerator.java` | 岛屿维度各自的下界/末地（空岛虚空式、海岛岩浆海） |
| `world/NaturalIslandPlacer.java` | 自然岛：地形 + 宝箱 + 刷怪笼 + **结构模板与底座**（跨 tick 补铺、落盘去重） |
| `world/StructureFoundations.java` | 悬空结构的底座（区块加载时按 `structureFoundations` 名单补，默认猪灵堡垒、不含要塞） |
| `feature/FishingLoot.java` + `feature/FishingPresets.java` + `mixin/FishingHookLootMixin.java` | 渔获：按维度挂表、按群系分池、稀有度（含岩浆表） |
| `feature/FishingBait.java` + `feature/FishingGameplay.java` | 副手钓饵（水/岩浆分档加速、钓到才扣）与岩浆可钓判定 |
| `feature/item/HubSuiteItems.java` + `feature/item/LavaRodItem.java` | 8 个自定义物品（Polymer 映射；`Item.Properties.setId` 必填） |
| `mixin/FishingHookMixin.java` + `FishingHookAccessor.java` + `FishingRodCastMixin.java` + `LavaFishingHook.java` | 岩浆算水（`tick` 里两处 `FluidState.is` 都要改）、钓饵加速、抛竿打标记 |
| `feature/CommandGuard.java` | 命令闸门（创造服屏蔽 40+ 条作弊指令） |
| `feature/PermissionService.java` | 三级权限降级 |
| `command/HubCommand.java` | `/hub` 命令族（`list`/`where`/`selftest` + 服务器参数） |

### 数据落盘位置（排查用）

**`config/hubsuite/`**（全局配置，跨子服共享）

| 文件 | 内容 |
|---|---|
| `accounts.db` | 账号库（SQLite） |
| `config.json` | 主配置（子服、种子、`ownNether`/`ownEnd`…） |
| `last-locations.json` | **坐标记忆** `玩家UUID → {维度key: [x,y,z,yaw,pitch]}` |
| `rules/<子服>.json` | 各子服 gamerule 快照 |
| `skyblock/islands.json` | **岛屿归属**（键是玩家 UUID） |
| `skyblock/quests.json` | 任务进度 |
| `update-check.json` | 更新检查缓存（ETag + 最近查到的版本，24h） |

**`run/hubsuite_<子服>/`**（每子服一份独立存档）

| 路径 | 内容 |
|---|---|
| `players/data/<uuid>.dat` | 背包 / 经验 / 血量 / 末影箱 |
| `players/data/advancements/<uuid>.json` | **成就**（按子服隔离） |
| `players/data/stats/<uuid>.json` | **统计**（按子服隔离） |
| `level.dat` | 这个世界的种子与设置 |
| `dimensions/hubsuite/<维度>/region/` | 区块 |

> 成就的**快速自检法**：在生存服拿个成就，看
> `run/hubsuite_survival/players/data/advancements/<uuid>.json` 是否出现、
> 同时 `run/world/players/data/advancements/` **不应该**增长。

---

## 九、Mixin 约定与清单（22 个）

### 约定（都踩过坑）

1. Mixin 类里**不能有非 private 静态方法** → `InvalidMixinException`。注入状态用外部持有者类（如 `CommandGuardHolder`）。
2. 普通代码**不能直接引用 mixin 包里的类** → `IllegalClassLoadError`。helper 放业务包，mixin 包只做转发。
3. `@ModifyExpressionValue` / `@At` 在同方法里有多个匹配时要**指定 ordinal**。
4. 改 record 字段：`@Shadow @Final @Mutable`，且在构造器 `RETURN` 之后改。

### 隔离关键（改这里就会串服）

| Mixin | 作用 |
|---|---|
| `PlayerListStorageMixin` | 替换 `PlayerList.playerIo` 的读写 → 背包按子服隔离 |
| `ServerLevelGameRulesMixin` | `ServerLevel.getGameRules()` 返回本子服的规则 |
| `PlayerListAuxDataMixin` | 成就 / 统计路径按维度重定向 + 换掉 `ServerPlayer` 的 final 引用 |
| `MinecraftServerLevelsAccessor` | 把自定义维度注册进 `MinecraftServer.levels` |
| `MinecraftServerStorageAccessor` | 访问 `storageSource` / `executor` |
| `PlayerListAccessor` | 自检走「和正常下线一样」的保存路径 |
| `PlayerListFieldAccessor` | 直读 `players` 原始列表（`getPlayers()` 会滤掉假人） |

### 世界 / 传送 / 重生成

| Mixin | 作用 |
|---|---|
| `ServerLevelSeedMixin` | 每个维度用自己的地形种子（否则生存 / 创造是同一张地图） |
| `BaseFireBlockPortalMixin` | 自有维度里火能点燃下界门（原版写死只认两个 key） |
| `PortalBlockDestinationMixin` | 每子服自己的下界 / 末地；**出口门建在正确维度** |
| `ServerPlayerRespawnDimensionMixin` | 死亡重生落在当前子服（原版读全局共享重生数据） |
| `PrepareSpawnTaskMixin` | 登录直接落大厅（避免先落主世界再传送） |
| `DistanceManagerRemovePlayerMixin` | 修原版对未登记区块的 NPE（切服中途「点进服务器没反应」） |
| `DistanceManagerAccessor` | 读 private `playersPerChunk` 做空值判断 |
| `StructureTemplatePaletteCacheMixin` | 修原版 `StructureTemplate$Palette` 缓存非线程安全（并行区块生成时 CME） |

### 假玩家（NPC）

| Mixin | 作用 |
|---|---|
| `PlayerListFakePlayerMixin` | 假人不计入在线人数 / 列表 |
| `PlayerInfoListedMixin` | 假人不出现在客户端 Tab（`Entry.listed` 硬编码 true） |
| `ConnectionChannelAccessor` | 给假玩家注入 `EmbeddedChannel` |

### 数据包与交互

| Mixin | 作用 |
|---|---|
| `ServerCommonPacketListenerMixin` | 接收对话框表单提交（`handleCustomClickAction`） |
| `ServerGamePacketListenerCommandMixin` | 命令闸门（Fabric 无「命令即将执行」事件） |
| `ServerGamePacketListenerMenuMixin` | 接管箱子式菜单点击 |
| `BlockItemPlaceMixin` | 任务精确统计「放置方块」（真的放下才计数） |

---

## 十、分支模型与发布流程

### 分支

| 分支 | 用途 |
|---|---|
| `main` | **正式版线**：只保留已发布的正式版状态，不直接在上面开发 |
| `dev` | **日常开发**：所有「未加入正式版」的修改都提交到这里（默认工作分支） |
| `next` | **测试版线**：预发布（rc / beta）通道 |

三个分支同源（都从 `main` 切出）。`dev` 领先，`next` 用来发测试版，`main` 只在发正式版时合并。

> **发版由人类作者通知 AI 触发**，AI 负责合并、打 tag、写 release。

### 发测试版（预发布）

```text
1. 把 dev 合并到 next
2. 在 next 上打 tag：v<版本>-rc.N
3. gh release create v<版本>-rc.N --prerelease --notes-file <说明.md> <jar> <sources.jar>
```

### 发正式版

```text
1. 把 dev（或 next）合并回 main
2. 在 main 上打 tag：v<版本>
3. gh release create v<版本> --notes-file <说明.md> <jar> <sources.jar>
4. 把 main 同步回 dev / next（避免下次合并冲突）
```

### 版本号规则（SemVer，当前 0.x）

版本 = `MAJOR.MINOR.PATCH`（SemVer 2.0.0）。**整体功能完成前 MAJOR 固定为 0**；
功能补齐、存档 / 配置格式冻结时升 `1.0.0`。

| 位 | 什么时候加 |
|---|---|
| `MAJOR` | 1.0 之前固定 0；`1.0.0` = 功能完成 + 格式冻结 |
| `MINOR` | 一批新功能 / 一个版本主题（`0.3.0`、`0.4.0`） |
| `PATCH` | 向后兼容的修复（`0.3.1`、`0.3.2`） |

0.x 阶段**不承诺兼容性**：不兼容改动照做，但必须在 release 的「升级注意」里写清（要不要删档 / 迁移）。

**MC 版本不写进版本号**（`fabric.mod.json` 的 `depends` 已锁），只在 release 标题 / 文档里标注。

### 预发布档位（四档，从小到大）

| 档 | 含义 | 场景 |
|---|---|---|
| `alpha.N` | 内测，功能可能不全 | 早期验证 |
| `beta.N` | 公测，功能基本齐 | 面向尝鲜玩家 |
| `pre.N` | 预发布，接近完成 | 正式前一轮 |
| `rc.N` | 发布候选，只差正式 | 正式前最后一轮 |

- `N` 从 1 递增；同一目标版本可以**跳档**（例如直接 `0.3.0-rc.1`）。
- 排序：`0.3.0-alpha.1 < alpha.2 < beta.1 < pre.1 < rc.1 < 0.3.0`。
- 发布用词只用这四档；**`-SNAPSHOT` 只留给 `dev` 本地构建，不发布**。
- GitHub 的预发布标志由 `gh release create --prerelease` 决定，和用哪个词无关。

### 版本号在各分支上的落点

| 分支 | `mod_version` |
|---|---|
| `dev` | `0.4.0-SNAPSHOT`（下一个正式版目标） |
| `next` | 发测试版时：`0.3.0-rc.1`，tag `v0.3.0-rc.1` |
| `main` | 发正式版时：`0.3.0`，tag `v0.3.0` |

tag 带 `v`；产物名跟随 `mod_version`（`hubsuite-0.3.0-rc.1.jar` / `hubsuite-0.4.0-SNAPSHOT.jar`）。

### 发版前固定步骤

```text
1. 改 gradle.properties 的 mod_version
2. 同步文档里的产物文件名（README.md / docs/**）
3. 跑一次自检确认 失败 0 项
4. 从 GitHub 真实下载校验：zip 有效、fabric.mod.json 的 version 对、类数对
```
**发布说明必须如实写**：哪些已验证、哪些没有、升级要不要删存档。不要为了让 release 好看而省略「未验证」。

### 面向使用者写（重要）

release 的**提交信息、tag message、release notes** 都是给**使用者（玩家 / 服主）**看的，
不是写给作者或未来的 AI 的：

- ✅ 写：这个版本对使用者**有什么变化**、怎么升级、要不要删存档、有哪些已知问题
- ✅ 用玩家能懂的话；必要的技术原因可以留一小段，但不要整篇堆字节码分析
- ❌ 不要出现「你观察到的…」「发我」这类**直接对作者说话**的措辞
- ❌ 不要把内部排查过程、重构细节、自检项数当成主要内容
- ❌ 不要拿只有开发者才关心的文件名 / 方法名当标题（可放进「技术细节」段）
- 未验证的项必须**明确标注**（例：「实机未验证」）—— 这是如实，不是丢脸

推荐结构：

```markdown
## 这个版本改了什么    使用者能感知的变化，每条一句话
## 升级注意            要不要删存档 / 迁移数据 / 重启
## 已知问题            未验证项、暂未修复
```

> 反面教材：把「你观察到的对比正好印证了这一点」「请把当时坐标发我」写进 release notes ——
> 那是给作者看的，不是给使用者看的。

---

## 十一、禁止事项

- ❌ 不要抢 **25565** 端口起测试服务端（会踢掉作者的客户端）
- ❌ 不要把 `build/`、`run/`、`mods/` 里的产物提交进版本库
- ❌ 不要为了「让自检变绿」而放宽断言 —— 宁可留一条红的，也不要假绿
- ❌ 不要在没有实测的情况下修改 README 里「已验证」的结论
- ❌ 不要一次性删掉别人写的大段注释（那些注释是踩坑记录，是本项目的资产）

---

## 十二、开发沿革（原 PROGRESS.md 并入）

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | 工程骨架（Gradle + Loom + Java 25 + 官方名） | ✅ |
| Phase 1 | 多世界引擎（每子服独立存档 / 规则 / 玩家数据） | ✅ |
| Phase 2 | 对话框注册 / 登录（SQLite + PBKDF2） | ✅ |
| Phase 3 | 假人大厅 NPC 选服 | ✅ |
| Phase 4 | 三子服玩法（生存 / 创造 / 空岛 classic + ocean） | ✅ |
| Phase 5 | HuskHomes / 占位符 / LuckPerms 适配 | ✅ |
| Phase 6 | 中文文档 + 全流程自测 | ✅ |

**已锁定的需求与决策（不要再改）**：

- 目标版本严格锁定 MC 26.1.2；纯服务端 Fabric 模组；单个服务端 + 每子服独立存档
- 包名 / mod id：`cn.dreamgary.hubsuite` / `hubsuite`
- 离线服务器（`online-mode=false`），但正版与离线都要注册 + 登录
- 登录用对话框表单（`TextInput` + `CustomAll` 静默回传）+ PBKDF2 加盐 + SQLite；密码不进聊天 / 命令 / 日志
- 大厅 NPC 用真 `ServerPlayer` 假人；空岛只做 classic + ocean，不做单方块
- 扩展性要求：新增子服尽量只改配置

**自检项数演进**：Phase 1 收尾 37 → 空岛三维度改造时 94 → 当前 **232**。

**已修复且经作者实测的关键问题**：

- 生存 / 创造曾是同一张地图（`ServerLevel.getSeed()` 全服一份，地形管线拿不到配置种子）
- 虚空维度里长出村庄（`Optional.empty()` 表示「没有结构覆盖」，会回落群系自带结构集）
- 传送门点不着（`BaseFireBlock.inPortalDimension` 写死只认 `overworld`/`the_nether`）
- 传送门另一端不生成出口门（建门发生在原版目标维度，只改返回值维度不够）
- 成就 / 统计没按子服隔离（`ServerPlayer` 自己持有两个 final 引用，只清 `PlayerList` 的 map 没用）
- 死亡重生串到别的子服（`findRespawnDimension()` 读全局共享的 `overworldData().getRespawnData()`）
- 关服丢玩家最终进度（清理放在 `SERVER_STOPPING`，随后原版 `saveAll()` 把状态写进主世界）

---

## 十三、未验证清单（不要当成已验证）

以下项**尚未实机验证**，报告 / 发布时按「未验证」对待：

> **v0.4.0 新增（全部只过了自检，实机未验证）**：岛屿保护整体删除；
> 岛上钓鱼（5 个池 / 40+ 条渔获 / 稀有度 / 群系分池）；岛屿维度各自的下界/末地
> （虚空式/岩浆海）；传送门落点下界岩小岛；自然岛 = 结构模板 + 底座 + 宝箱 + 刷怪笼；
> 猪灵堡垒自动补底座（要塞不补）。

1. **传送门端到端**：自检只验了「能点火」和「改道映射」，**没有真的让玩家钻进去**。
   v0.2.5 修了「出口门建在正确维度」但**仍未实机验证**。
   > 注意：用 `/setblock` 直接放火**点不着门**（连原版主世界也不行）——
   > `BaseFireBlock.onPlace` 首句是 `if (previousState.is(this)) return;`，必须靠**火蔓延**才会触发建门检测。
   > 请用真客户端 + 打火石实测。
2. **自检缺一条「真死一次再看重生维度」的用例**：现有那条只断言切换子服后 `respawnConfig` 正确，
   所以它一直是绿的，却漏掉了 v0.2.5 修的「重生串服」。
3. **成就的边角情况**：现场用 `/advancement grant` 授予的成就仍会先落旧路径，下一次切服才归位。
   怀疑是 RCON 授予时目标玩家对象与机器人实例不一致，**未定论**。
4. **下界 / 末地的地形**：生成器参数取自原版注册表，没实地看过。
5. **冷存档自检可能有几项红**（与阻塞无关）：出生点断言可能跑在补算完成之前；
   海洋冰冻群系占比受随机种子影响；`run/mods` 缺 HuskHomes 时集成项会红。
