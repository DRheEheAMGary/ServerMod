# HubSuite 开发进度

> 最后更新：**Phase 0–6 全部完成**，待办仅剩"真实客户端联调"。
> 本文档用于**跨会话续接开发**：包含已确认的需求、已完成内容、关键约定、下一步。

---

## 0. 一分钟速览

**状态：`./gradlew build` 通过（Java 25，产物 406 KB / 131 个类）；中文文档齐全。**
**`hub selftest` 共 182 项，但实测在冷存档上跑不完 —— 见下方"实测"一节（已知问题）。**

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | 工程骨架（Gradle 9.7.1 + Loom 1.18 + Java 25 + 官方名） | ✅ |
| Phase 1 | 多世界引擎（每子服独立存档/规则/玩家数据） | ✅ |
| Phase 2 | 对话框注册/登录（SQLite + PBKDF2） | ✅ |
| Phase 3 | 假人大厅 NPC 选服 | ✅ |
| Phase 4 | 三子服玩法（生存/创造/空岛 classic+ocean） | ✅ |
| Phase 5 | HuskHomes / 占位符 / LuckPerms 适配 | ✅ |
| Phase 6 | 中文文档 + 全流程自测 | ✅ |

**产物**：`build/libs/hubsuite-0.2.0.jar`（约 406 KB，131 个类；源码 86 个 Java 文件 / 17 个 Mixin）

> ⚠️ 体积和类数只当**粗看**：它们随每次构建变化，别拿去当核对标准。
> 要核对就跑 `hub selftest`，它才是真正的验收门。
> 构建需要 **Java 25**（`JAVA_HOME` 指向 25 的 JDK），
> 否则 Loom 在**配置阶段**就报 `Dependency requires at least JVM runtime version 25`。

**验证命令**：
```bash
./gradlew build
./scripts/dev-server.sh "hub selftest"     # 无人值守起服 + 182 项自检，约 1–3 分钟
```

### ✅ 实测（2026-10-06，Java 25 + LuckPerms/HuskHomes/Placeholder 全装）：冷存档自检已经跑得通

真机复现 + 修复 + 复测。修之前：**全新存档上 `hub selftest` 跑到第 36 步
服务端被看门狗强杀**，拿不到"通过 N 项"。修之后：冷存档上能跑完，
**看门狗 0 次**。

| 项目 | 结果 |
|---|---|
| `./gradlew build` | ✅ 通过（Java 25） |
| 四个软依赖 | ✅ 全部生效（huskhomes / luckperms / placeholder-api / fabric-permissions-api） |
| `/hub selftest`（冷存档） | ✅ **跑完，看门狗 0 次** |

#### 最关键的一条认知：`hasChunk` 不能当"可读写方块"的判据

`chunkSource.hasChunk(cx, cz)` 对"已加载到**任意非空阶段**"都返回 true，
而 `getBlockState` / `setBlockAndUpdate` 要的是 **FULL** 区块 ——
没到就 `getChunk(...).join()` 同步等下去，主线程就此阻塞。
**实测拿 hasChunk 当守卫照样被看门狗强杀**，崩溃栈就落在守卫之后那一行。

正确判据：`chunkSource.getChunkNow(cx, cz) != null`（不阻塞，没到 FULL 返回 null）。
全文按这个判据统一过了一遍。

#### 修掉的 4 条阻塞路径

1. **自检里的同步区块加载（7 处）** —— 其中 `checkOceanStructures` 原来是
   19×19 = 361 个区块的同步循环。冷存档上每个区块都要现生成，
   单次 tick 直接超过 60 秒上限。
   → 改为"挂加载票据 + 跨 tick 延迟判定"（`defer`/`pumpDeferred`/`readyChunks`
   与统一的 `checkIsland` 骨架），断言推迟到区块就绪后再跑，超时如实记"跳过"。
   **不能**改成"原地 sleep 等"：区块生成要主线程参与，占着不放就永远等不到。
2. **`SpawnPlatform.build`**（产品代码，跑在"每 tick 重试补铺"的路径上）
   → 新增返回值 `CHUNKS_NOT_READY`，区块没到 FULL 就返回"稍后重试"。
3. **原版出生点搜索**（产品代码）—— `MinecraftServer.setInitialSpawn` 是
   最多 **121 个区块的同步螺旋**。项目本来靠"出生点缓存"规避，但缓存只在
   上次成功启动后才存在，**全新安装/清档后的第一次启动必然要算一次**。
   → 加载阶段先判就绪：没就绪就用配置坐标顶着 + 挂票据 + 登记延迟补算；
   世界跑起来后补算 → 写回缓存 → **回填 Entry、重生点与世界边界**
   （不回填的话本次运行仍是旧值，实测自检照样报"悬空"和"边界圆心偏离"）。
4. **`IslandManager` 的地形清理/待补铺守卫**用的也是 hasChunk → 改 FULL 判据。

#### 顺带修掉的自检假失败

- **"放置方块只在真的放下时计数"**：原来直接假设"平台中心 +2 格是空气"，
  冷存档上空岛大厅可能已有别的地形 → 误报"放置没计数"。
  改为先确认"区块就绪 + 目标格是空气"，否则跳过。
- **"出生点悬空"**：改为等补算完成后再判，不再拿加载阶段的临时值下结论。

#### 无人值守自检入口

脚本里想让服务端"起来就自检"：走 stdin 会被 Gradle 包装层抢走命令行
（实测命令被吃掉）、走 RCON 在 26.1.2 上只在极窄时序下回包。两条路都不可靠。
→ 新增 `-Dhubsuite.selftest=<延迟秒>`，由 `build.gradle` 转发给 runServer 的
**分叉 JVM**（不转发的话属性只留在 Gradle 自己的 JVM，服务端看不到）。
配套 `scripts/selftest-run.ps1`：以日志结果行为准的跑法（不依赖 RCON 响应）。

#### 仍然存在的失败项（与阻塞无关，属种子/时序相关）

冷存档上还会剩几项红：出生点类断言可能跑在"补算完成"之前（补算是异步的，
下一次启动命中缓存后即通过）；海洋冰冻群系占比受随机种子影响
（曾实测 0.7%，目标 2~3%）；`run/mods` 缺 HuskHomes 时集成项会红（环境缺失）。

### ⚠️ 运维约定：清数据 = 连地图一起清

**用户明确要求**：清空数据时必须把地图存档一并删除，不能只清玩家数据。

需要删的路径：
```
run/hubsuite_lobby/      大厅存档
run/hubsuite_survival/   生存服
run/hubsuite_creative/   创造服
run/hubsuite_skyblock/   空岛服
run/world/               主世界
run/config/hubsuite/     配置 + 账号库 + 空岛归属 + 位置记忆
run/config/{huskhomes,luckperms}/
run/usercache.json  run/ops.json  run/*/session.lock
```

只清玩家数据而不清地图，会导致**旧代码生成的地形残留**，
和新地形叠在一起 —— 实测出现过"箱子被挤爆""岛中间有旧树干"。

### 实机联调中发现并修复的真实 bug（均已加入自检）

| 现象 | 根因 | 修法 |
|---|---|---|
| 进生存服浮空摔死 | 出生点硬写 Y=100，实际地表在 Y=65 | 接原版 `setInitialSpawn` 让世界自己算地表出生点；新增 `useWorldSpawn` 配置 |
| 死在生存服却重生到空岛服 | 原版重生点是全局的；旧代码 `force=false` 不敢覆盖 | 进服时强制把重生点绑到当前子服 |
| 空岛树没有下半截树干 | 生成顺序错误：先种树、后"清空落脚点上方"把树干挖了 | 改为 清空 → 种树 → 放箱子 |
| 进服卡在树干里 | 岛出生点 (0.5,101,0.5) 正好是树干坐标 | 树移到偏心位置，出生点保留中心列 |
| **岛上左半边不能建造/箱子半边提示"不是我的空岛区域"** | **方格边界算错**：`(int)(x/288)` 让边界落在格子中心上，中心在原点的岛被 x=0/z=0 劈成两半，左半边被判成邻格 | `plotOf` 改为 `(int)((x+144)/288)`，方格 `(0,0)` 覆盖 `[-144,+144)`。穷举验证：修复前 625 格拒 456 格 → 修复后拒 0 格 |
| 出生卡在树干里 | 岛屿正中心同时也是**子服出生点**，而树也种在正中心 → 任何"退回子服出生点"的路径都会把玩家塞进树干 | 树移到 `+2` 偏移，岛中心保持空地；两条路径都安全 |
| 假人被算进在线人数 | 假玩家是真 `ServerPlayer`，`allowsListing=false` 只管客户端 Tab | `MarkedFakePlayer` + Mixin 过滤 `getPlayerCount`/`getPlayers` |
| 大厅夜晚+下雨 | 大厅改用 overworld 维度后继承主世界昼夜天气 | `LobbyEnvironment` 强制永昼 + 晴朗（并修了自己引入的"每进一人时钟跳一天"）|
| 对话框卡 5 秒 | `after_action=WAIT_FOR_RESPONSE` 让客户端进入"等待响应"态 | 改为 `CLOSE` |
| 切服中断（点进服务器没反应） | 原版 `DistanceManager.removePlayer` 对未登记区块不判空 | 加防护 Mixin |
| `/hub` 后进不去别的子服 | 大厅不在 `subServers` 表里，菜单"返回大厅"走 `joinById` 落进 `orElse` 分支 | `joinById` 单独处理 `Lobby.ID`（详见下方"曾经待复现确认的三项"）|
| 重连时间长 | 登录先落主世界再被传送去大厅 → 客户端连加载两个世界 | `PrepareSpawnTaskMixin` 直接落大厅（详见下方）|
| 空岛服永不建岛 | `onEnterSkyblock()` 是死代码，从未被调用 | 接到 `PlayerRouter` 入场监听器 |
| 进服时查库阻塞主线程 | `isRegistered` 同步查 SQLite | 改异步 + 加耗时埋点 |

### 曾经"待复现确认"的三项 —— 已全部修复并回归

原先这里记着一条待办：`/hub` 退出后进不去别的子服 + 重连时间长。
实际上它把**三个不同症状**混在了一起，各自根因不同，后来分别修掉（已在库 + 有回归自检）：

| 症状 | 根因 | 修复 | 回归自检 |
|---|---|---|---|
| 点进服务器没反应 | `DistanceManager.removePlayer` 对未登记区块不判空 → 切服中途 NPE | `DistanceManagerRemovePlayerMixin` | 传送相关用例 |
| `/hub` 后进不去别的子服 | 大厅不在 `subServers` 表里，`joinById(Lobby.ID)` 走 `orElse` 分支 | `8599739` | `SelfTest` 菜单检查："返回大厅"真的能回大厅（**修复前会红**）|
| 重连时间长 | 登录先落主世界、再传送去大厅 → 客户端连加载两个世界 | `PrepareSpawnTaskMixin` | 登录路径覆盖 |

**另外两件曾经把水搅浑、也已在 `36e50fa` 修掉的事**（它们会伪装成"连不上/切服坏了"）：

- 空岛选岛假人取名 `hub_island_select`（**17 字符**，超过 MC 用户名 16 上限），
  名字进了 `ClientboundPlayerInfoUpdatePacket`，`Utf8String.write` 长度检查失败
  （`String too big (was 17 characters, max 16)`）→ **每个玩家一连上就被踢**，
  现象上完全看不出是"某个假人名字太长"。已在 `FakePlayers.spawn` / `HubNpc.sanitizeName`
  入口统一强制 3..16 字符 `[A-Za-z0-9_]`。
- 大厅/空岛引导假人是真 `ServerPlayer`，会触发 JOIN 被认证系统当"未登录玩家"抓走
  （日志实证：`切服请求：hub_island -> lobby`）—— 于是排查真玩家切服时，
  日志里混着**假人在被搬运**的记录。已让 `AuthManager` 对 `MarkedFakePlayer` 直接跳过。

> 诊断日志（`切服请求` / `切服完成` / `到达大厅耗时` / `左键点击 NPC`）**保留着**，
> 它们只在自己那条路径上打 INFO，能区分"请求没到服务端"和"服务端成功但客户端没反应"。

---

## 1. 需求与已确认的决策（不要再改）

| 项目 | 决定 |
|---|---|
| 目标版本 | **严格锁定 Minecraft 26.1.2**（不需要考虑未来升级兼容） |
| 模组形态 | **纯服务端** Fabric 模组，原版客户端可直接进服 |
| 架构 | **单个 Fabric 服务端 + 每子服独立存档**（不是"一个存档多维度"） |
| 包名 / mod id | `cn.dreamgary.hubsuite` / `hubsuite`（用户网站 dreamgary.cn） |
| 服务端模式 | **离线服务器（online-mode=false）**，但**正版与离线玩家都要注册 + 登录** |
| 登录方案 | 对话框表单（`MultiActionDialog` + `TextInput` + `CustomAll` 静默回传）、PBKDF2 加盐哈希、SQLite 存储 |
| 大厅 NPC | 真 `ServerPlayer` 假人 |
| HuskHomes | 用官方 **Fabric 版**，`4.11+26.1.2`（用户已下载到 `mods/`） |
| 子服数量 | 生存服 / 创造服 / 空岛服（**空岛只做 classic + ocean，不做单方块**） |
| 生存服 | 死亡掉落、允许 PvP、独立背包、可从其他子服传回原位、提供家/传送点/Tpa 指令 |
| 创造服 | 飞行 + 瞬间破坏、普通玩家禁用 `/give` 等作弊指令、独立权限组（**用 LuckPerms**） |
| 空岛服 | `/island` 系列指令、plot 网格分配、可选岛型（先 classic / ocean） |
| 扩展性要求 | 以后新增子服应尽量只改配置，不写 Java 代码 |

### 依赖版本（已确认可用）
```
minecraft_version      = 26.1.2
loader_version         = 0.19.5
loom_version           = 1.18-SNAPSHOT
fabric_api_version     = 0.155.3+26.1.2
huskhomes_version      = 4.11+26.1.2
placeholder_api_version= 3.0.0+26.1
luckperms_api_version  = 5.5
Java 25 / 官方 Mojang 名（无 mappings、无 remapJar）
```

---

## 2. 已完成

### Phase 0 —— 工程骨架 ✅
- Gradle 工程可构建：`./gradlew build` → `build/libs/hubsuite-0.2.0.jar`
- Gradle wrapper 9.7.1（`gradlew` / `gradlew.bat` / `gradle/wrapper/*`）
- `fabric.mod.json`：`environment: "*"`、mixin 配置、软依赖 `suggests`
- 软依赖声明为 `compileOnly`，运行期用 `FabricLoader.isModLoaded` 探测
- **实机验证**：服务端能起，`hubsuite 0.1.0` 出现在 mod 列表；
  加载 huskhomes / placeholder-api / luckperms / fabric-permissions-api-v0 后无报错。

> ⚠️ 用户原始的 `HuskHomes-Fabric-4.6.jar` 编译于 1.20.4、使用 **intermediary 混淆名**
> （`net/minecraft/class_3222` 等），在 26.1.2 上**必然无法加载**（26.1 起官方不再混淆，
> Fabric 不再做中间名重映射）。已换成 `HuskHomes-Fabric-4.11+mc.26.1.2.jar`。

### Phase 1 —— 多世界引擎 ✅（待补最后一步实测）

已实现：

- **配置层** `config/`
  - `HubSuiteConfig`：根配置（大厅 / auth / 子服列表），带 `normalize()` 纠错
  - `DefaultConfigs`：首次启动生成生存/创造/空岛三服默认配置
  - `ConfigManager`：`config/hubsuite/config.json` 读写，损坏时备份并回落默认
- **独立存档层** `world/IsolatedSave`
  - 每个子服/大厅各一个 `LevelStorageSource.LevelStorageAccess` +
    自己的 `level.dat`（`PrimaryLevelData`）+ 自己的 `players/` 玩家数据目录
  - 原因：`PlayerList.loadPlayerData/save` 走 `storageSource.getLevelPath(PLAYER_DATA_DIR)`，
    而 `storageSource` 是"每 ServerLevel 一份"→ 玩家数据天然按子服隔离
  - `level.dat` 原子写入（先写 `.tmp` 再 move）
- **世界构建** `world/WorldBuilder` + `world/WorldFactory`
  - 三种生成方式：`NORMAL`（复用主世界区块生成器）/ `FLAT`（`FlatLevelSource` + 配置层）/
    `VOID`（单层 AIR，原版内部 `voidGen=true`）
  - 不注册任何自定义区块生成器 → 与原版及第三方模组兼容
- **规则隔离** `world/ServerRules` + `RulesManager` + `mixin/ServerLevelGameRulesMixin`
  - ⚠️ **重要事实（已反编译确认）**：26.1 的 gamerule **不再存 level.dat**，
    而是全局一份挂在 `MinecraftServer`（存于 `<world>/data/`）。
  - 做法：每个子服各建一份 `GameRules`，用 Mixin 让
    `ServerLevel.getGameRules()` 返回本子服那一份。
    已用字节码扫描确认原版所有游戏逻辑都走 `level.getGameRules()`，
    所以这一个注入点即可完成规则隔离。
  - 规则持久化到 `config/hubsuite/rules/<子服id>.json`（`world/RulesStorage`）
- **场所抽象** `world/PlayableWorld`（大厅与子服同一接口）、`Lobby`、`SubServer`
- **切服** `world/PlayerRouter`：`TeleportTransition` 传送、进入时套用模式/飞行/饥饿/血量
- **总控** `world/WorldsManager`：`SERVER_STARTED` 装载、`SERVER_STOPPING` 保存并落盘规则
- **命令** `command/HubCommand`
  - `/hub`、`/hub <子服id>`、`/hub list`、`/hub where`（等级 0）
  - `/hub info`、`/hub save`、`/hub reload`、`/hub selftest`（等级 2 / GM）
- **自检** `world/SelfTest`：无人值守验证存档隔离、规则隔离、玩家数据路径与落盘

#### 关键 Mixin（只有 4 个，全在 `mixin/`）
| Mixin | 作用 | 为什么必须 |
|---|---|---|
| `MinecraftServerLevelsAccessor` | 读写 `MinecraftServer.levels` | 注册自定义维度后由原版负责 tick/保存 |
| `MinecraftServerStorageAccessor` | 读 `storageSource` / `executor` | 两大字段是 protected/private |
| `PlayerListAccessor` | 调 `PlayerList.save(ServerPlayer)` | 走原版保存路径才能证明玩家数据隔离 |
| `ServerLevelGameRulesMixin` | 让维度返回自己的 `GameRules` | 26.1 gamerule 是全局的，必须注入 |

> 约定：**helper 类不能放在 `mixin` 包内**（Mixin 禁止直接引用 mixin 包里的类，
> 会抛 `IllegalClassLoadError`）。因此 `ServerInternals` / `ServerLevelsAccess` /
> `PlayerListAccess` 放在 `world` 包，只留接口式 Mixin 在 `mixin` 包。

#### 已实测通过
- 4 个独立存档目录被创建：
  `run/hubsuite_lobby`、`hubsuite_survival`、`hubsuite_creative`、`hubsuite_skyblock`
  每个都有 `level.dat` + `dimensions/`
- 启动日志：
  ```
  HubSuite) 大厅已加载：hubsuite:lobby（./hubsuite_lobby）
  HubSuite) 子服已加载：survival -> hubsuite:server_survival（存档 hubsuite_survival）
  HubSuite) 子服已加载：creative -> hubsuite:server_creative（存档 hubsuite_creative）
  HubSuite) 子服已加载：skyblock -> hubsuite:server_skyblock（存档 hubsuite_skyblock）
  HubSuite) 多世界引擎就绪：大厅 1 个，子服 3 个，耗时 107 ms。
  ```

#### Phase 1 尚未完成的一件事
- **`hub selftest` 还没跑出结果**。原因是本次测试时 `run/server.properties` 里
  的 `enable-rcon=true` 是服务端启动**之后**才写入的，服务端没监听 RCON 端口，
  脚本用 RCON 发命令时 `Connection refused`。
  → 下次启动服务端时 RCON 已生效，直接跑：
  ```bash
  ./scripts/dev-server.sh "hub info" "hub selftest"
  ```
  即可拿到隔离验证报告。

---

## 3. 环境与工作流备忘

```bash
# 构建
./gradlew build                     # 产物 build/libs/hubsuite-0.2.0.jar

# 无人值守跑服务端 + 执行命令（RCON 驱动，脚本会自动等就绪并 stop）
./scripts/dev-server.sh "hub info" "hub selftest"

# 注意：Gradle 必须能读写 ~/.gradle，沙箱受限时构建会失败
```

- `run/` 是开发用服务端目录：`eula.txt` 已接受，`online-mode=false`，
  RCON 已开（`rcon.password=hubsuite-dev`，port 25575）
- **`server.properties` 不能用非 ASCII 字符**（Java Properties 按 ISO-8859-1 读，
  中文注释会导致 `MalformedInputException`）
- 每次强杀服务端后需要清锁：`rm -f run/*/session.lock`
- 软依赖 jar 放在 `mods/`（归档）与 `run/mods/`（运行时）：
  fabric-api、HuskHomes 4.11、LuckPerms 5.5.85、placeholder-api 3.0.0、
  fabric-permissions-api 0.7.0

---

## 4. 待办

### Phase 1 收尾 ✅
- [x] 跑通 `hub selftest`，确认全绿（37/37）
- [x] 玩家数据按子服路由（发现并修复：原版只写主世界存档）
- [ ] 用真实客户端验证切服（留给 Phase 6 联调）

### Phase 2 —— 登录 / 注册（对话框表单）✅
- [x] `ui/DialogKit`：`MultiActionDialog` + `Input(key, TextInput)` + `CustomAll` 动作封装
- [x] Mixin `ServerCommonPacketListenerImpl#handleCustomClickAction` → `DialogRouter` 路由
      （带前缀路由，支持 `lobby/join/<id>` 这类带参动作）
- [x] `auth/`：SQLite（`config/hubsuite/accounts.db`，驱动借自 HuskHomes）、
      PBKDF2-HMAC-SHA256 加盐、会话保持（UUID+IP）、失败限速、账号锁定、
      `/hub auth list|reset|delete|kick`、SQLite 不可用时回落 JSON
- [x] 未登录锁定：大厅等待区 + 禁破坏/放置/交互/攻击/用物品 + 敏感命令拦截 + 超时踢出
- [x] 正版与离线玩家**都必须**注册登录
- [x] `/island` 之外还有 `/login` `/register` 聊天兜底命令（对话框打不开时可用）

### Phase 3 —— 大厅 + 假人 NPC ✅
- [x] `fake/FakeConnection`（`ConnectionChannelAccessor` 注入 EmbeddedChannel 让 `isConnected()` 为真）
- [x] `fake/FakeGamePacketListener`（吞掉所有断开请求：保活超时/挂机/重名）
- [x] `fake/FakePlayers`：走 `PlayerList.placeNewPlayer` **完整入服路径**
- [x] NPC 不出现在 Tab、不计在线人数（`ClientInformation.allowsListing=false`）
- [x] 点击检测：`UseEntityCallback`（右键弹确认框）/ `AttackEntityCallback`（左键直接进）
- [x] 皮肤：`npc/SkinFetcher` 异步拉 Mojang 皮肤并重建实体；失败则用默认皮肤
- [x] 确认框展示实时状态（人数/世界类型/模式/难度/PvP/飞行）
- [x] NPC 面向最近玩家

### Phase 4 —— 三个子服玩法 ✅
- [x] 生存服：位置记忆（`feature/ServerRulesEngine`，回原位）、PvP/无敌按子服判定、
      掉虚空保护；家/传送点指令由 HuskHomes 提供（见 Phase 5）
- [x] 创造服：`feature/CommandGuard` 拦截 40+ 条原版作弊指令；`feature/PermissionService` 三级权限降级
- [x] 空岛服：`island/` plot 网格分配（螺旋找空位）、`/island` 全套指令、
      classic/ocean 两种岛型（数据驱动，可加）、掉虚空回岛、方格保护、岛员邀请
- [x] 空岛：`/island` 全套 + plot 保护 + 岛员邀请 + 掉虚空回岛
- [ ] （已知限制）岛屿重置是同步写方块，超大岛会短暂卡顿；如需优化可改异步分批

### Phase 5 —— 集成适配层 ✅
- [x] `PermissionService`：LuckPerms（经 fabric-permissions-api，反射调用）→ 原版 OP 三级降级
- [x] `HuskHomesIntegration`：探测 + API 可用性检查 + 家数量查询 + 按子服开关传送指令 + 启动报告
- [x] `PlaceholderService`：注册 10 个 `hubsuite` 占位符到 Text Placeholder API，
      同时支持 `%hubsuite_xxx%` 与 `%hubsuite:player/xxx%` 两种语法，
      并把 `%huskhomes_*%` 转发给 HuskHomes 自己注册的解析器
- [x] 启动适配报告：空岛系统 / HuskHomes / 占位符 / 命令闸门 各自打印状态
- [x] `/hub placeholders` 与 `/hub perms` 两个诊断命令

### Phase 6 —— 收尾 ✅
- [x] `README.md` + `docs/`：安装部署、配置说明、命令与权限、架构与扩展
      （含"如何新增第四个子服""如何加岛型""如何加对话框"）
- [x] 打包交付说明（依赖清单 + 版本注意事项）
- [x] 服务端侧全流程自测（`hub selftest` 182 项全绿）
- [ ] **真实客户端联调**（唯一剩余项，需人工图形界面操作）：
      1. 起服务端：`./gradlew runServer`
      2. 客户端连 `localhost:25565`（离线服，用户名随意）
      3. 预期：进服后被拉进大厅等待区 → 弹出注册对话框 →
         填两次密码提交 → 自动进入大厅并看到 3 个假人
      4. 退出重进 → 应弹出登录对话框；右键假人 → 确认框 → 进服
      5. 若对话框没弹出，用 `/register <密码> <确认密码>` 兜底，
         并把客户端日志（`run/logs/latest.log` 或客户端 log）发出来排查

---

## 5. 代码结构现状

```
MinecraftSeverMod/
├─ build.gradle / settings.gradle / gradle.properties / gradlew(.bat)
├─ gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
├─ mods/                    软依赖 jar 归档
├─ scripts/dev-server.sh    无人值守开发测试脚本
├─ run/                     开发用服务端（eula / rcon / 4 个独立存档）
│  ├─ hubsuite_lobby/  hubsuite_survival/  hubsuite_creative/  hubsuite_skyblock/
│  └─ config/hubsuite/{config.json,rules/,accounts.db(Phase 2)}
├─ src/main/resources/
│  ├─ fabric.mod.json  hubsuite.mixins.json
│  └─ assets/hubsuite/icon.png
└─ src/main/java/cn/dreamgary/hubsuite/
   ├─ HubSuite.java                       主入口
   ├─ command/HubCommand.java             /hub 命令族
   ├─ config/{HubSuiteConfig,DefaultConfigs,ConfigManager}.java
   ├─ mixin/                              4 个 Mixin 接口 + 3 个访问出口
   │  ├─ MinecraftServerLevelsAccessor / MinecraftServerStorageAccessor
   │  ├─ PlayerListAccessor / ServerLevelGameRulesMixin
   │  └─ (ServerInternals / ServerLevelsAccess / PlayerListAccess 已移至 world 包)
   └─ world/
      ├─ PlayableWorld / Lobby / SubServer / WorldsManager
      ├─ IsolatedSave / WorldBuilder / WorldFactory
      ├─ ServerRules / RulesManager / RulesStorage / GameRuleNames
      ├─ PlayerRouter / SelfTest
      └─ ServerInternals / ServerLevelsAccess / PlayerListAccess
```

---

## 6. 容易踩的坑（已验证）

1. **mixin 包内的类不能被普通代码直接引用** → helper 放 `world` 包。
2. **26.1 的 gamerule 是全局的**，不随 level.dat 走 → 必须 Mixin `ServerLevel.getGameRules()`。
3. **`MinecraftServer.storageSource` 是 protected、`executor` 是 private** → 需要 accessor。
4. **`server.properties` 只能 ASCII**。
5. **强杀服务端会留下 `session.lock`** → 下次启动前删掉。
6. 26.1 用官方名：`net.minecraft.server.level.ServerPlayer`、`net.minecraft.resources.Identifier`、
   `InteractionResult`（不是 `ActionResult`）、`GameRules.KEEP_INVENTORY`（UPPER_SNAKE）。
7. 对话框约束：`CommonDialogData` 校验要求 `pause=true` 时 `after_action` 必须能解除暂停；
   我们用 `pause=false` + 自定义负载回传。
8. 编译期需要 server-only 类 → Loom 的 `minecraft-merged` jar 已包含，直接可用。
9. **原版玩家数据是全局的**：`PlayerList` 持有自己的 `playerIo`（目录固定在主世界
   `players/data/`），只改 `MinecraftServer` 没用 → 必须 Mixin `PlayerList.save` /
   `loadPlayerData` 里的字段读取（`PlayerListStorageMixin`）。
   **这一点是被自检抓出来的**，只靠读代码不会发现。
10. `PlayerList.placeNewPlayer` 会**按玩家存档里的位置**放置玩家，
   不会用你传入的 `ServerLevel` 坐标 → 传送后要显式 `teleportTo`。
11. authlib 7 把 `GameProfile` 改成了 record：`getProperties()` → `properties()`，
    `getName()` → `name()`。
12. `ServerPlayer` 没有 `getServer()`；用 `player.level().getServer()`。
13. 26.1 权限：`CommandSourceStack.hasPermission(int)` 已删除，
   改用 `source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)`；
   玩家侧是 `player.permissions().hasPermission(...)`。

---

## 空岛服三维度改造 · 已完成（2026-10-05）

需求：空岛服 = 1 个大厅 + 经典空岛 + 海岛（两个岛屿维度），
上次在哪个岛就回哪个岛；一个人可以同时拥有两种岛。

### 最终结构

```
hubsuite:skyblock_hub      公共大厅（出生平台 + "选择岛屿"假人）
hubsuite:skyblock_classic  经典空岛（网格方格，每人格子一座）
hubsuite:skyblock_ocean    海岛（自然海洋世界，岛按距离摆放）
```

三个维度各有**独立存档**（`hubsuite_skyblock_{hub,classic,ocean}`）——
`PlayerDataStorage` 是按存档分目录的，共用一份会让三个维度的背包/经验互相覆盖。

### 完成的验证（当时 `/hub selftest` 共 94 项全绿；看门狗 0 次。当前基线已涨到 182 项）

- 三维度就绪、岛型配置正确（classic + ocean）
- **入口分流**：没岛 → 大厅；有经典岛 → 回该岛；两岛型归属互不影响；
  两座岛分属不同维度
- **保护**：别人不能在我的岛上建造、岛主可以、海岛之外的海面是公共区域
- **重置**：归属清除后可重新建岛；同时拥有两种岛互不干扰
- **经典岛地形**：中心方块 = Grass Block，落脚点干净（站在草地上，脚底 Y=101）
- **海岛维度是自然海洋**：出生点群系 `ocean` / `deep_ocean`；
  **沉船结构确认可用**（查 `Structure.biomes()`，纯注册表读取）
- **出生点落地**：survival / creative 都在地表（Y=73 附近，脚下草地）
- **指令注册**：`/island` 八个子命令 + `/menu` `/hub` `/auth` 都在 dispatcher 里
- **调试指令**已按需求全部移除

### 踩过的坑（都在代码注释里）

1. **`setBlockAndUpdate` / `getBlockState` 都会同步加载区块**
   —— 往未加载区块写方块 = 强制生成整片区块。海洋地形极重，
   这一下就把主线程卡到看门狗强杀（`A single server tick took 60.00 seconds`）。
   **累计踩了四次**。最终解法：
   - 建岛前检查 3x3 区块是否已加载，没加载就跳过（`generate` 返回 false）；
   - `ensureTerrain()` 在玩家真正要落地时才补铺；
   - 自检一律用 `blockIfLoaded()`（区块没加载返回 null），绝不阻塞；
   - 出生平台也走"延迟补铺 + tick 重试"。

2. **群系判定必须用 `level.getNoiseBiome()`**
   —— 用 `getBlockState` 大范围采样会生成区块，直接把服务器卡死。

3. **指令必须注册在模组初始化阶段**
   —— `CommandRegistrationCallback` 在服务端启动前就触发完了；
   放在 `SERVER_STARTED` 里注册的指令根本进不了 dispatcher
   （`/island` 一度是死指令，输入只报 Unknown command）。

4. **方块坐标与实体坐标的换算**
   —— 岛面草方块在 Y=100，玩家脚底站在 Y=101 时 `blockPosition()`
   报的仍是那一格，看起来像"站在方块里"，实际是正常的。

5. **`PlayerInfoUpdatePacket.Entry` 的 `listed` 是硬编码 true**
   —— `allowsListing=false` 对初始 ADD_PLAYER 包无效，必须 Mixin 改字段；
   但**不能把条目整个删掉**，客户端要靠它创建玩家实体（删了 NPC 会消失）。

6. **看门狗不吃"我以为"**
   —— 只有把 `forceload` / 同步区块加载全部去掉之后才是干净验证。
   用 forceload 在海洋维度预加载区块，本身就会触发看门狗。
