# AGENT.md — 给 AI Agent 的工作约定

> 本文件是**长期有效的工作约定**：怎么搭环境、怎么改、怎么验、什么不能做。
> **当前进度与待办**在 [`HANDOFF.md`](HANDOFF.md)（会随每次修复更新）。
>
> 这个项目由人类作者与 AI Agent 协作开发，详见 README 的「AI 参与声明」。

---

## 一、动手前必读

1. **先读 [`HANDOFF.md`](HANDOFF.md)** —— 里面有当前版本、已验证/未验证清单、待办、
   以及一张「踩过的坑」表。很多坑踩一次要花几小时，表里都写了。
2. **说话用中文**（作者是中文用户）。
3. **不要声称验证过没验证的东西**。没验就明说"未验证"。这是本项目最重要的一条约定 ——
   作者会亲手实测，虚报会被当场抓到，而且会浪费他更多时间。

---

## 二、项目速览

**HubSuite** —— Minecraft **26.1.2** 的**纯服务端** Fabric 模组（Java 25）。
把一个服务端变成「大厅 + 多个互相独立的子服」（生存 / 创造 / 空岛）。

| 项 | 值 |
|---|---|
| 模组 id / 组 | `hubsuite` / `cn.dreamgary` |
| 版本 | `gradle.properties` 的 `mod_version` |
| 构建 | Gradle 9.7.1 + Loom 1.18-SNAPSHOT |
| 映射 | **官方 Mojang 名，不 remap**（26.1 起官方不再混淆） |
| 产物 | `build/libs/hubsuite-<版本>.jar` |

### 架构要点（改之前必须理解）

- 每个子服/维度是**一个独立存档**（`IsolatedSave`，各一份 `LevelStorageAccess`）
- 每个存档有**独立玩家数据**（背包/经验/成就/统计都按子服隔离）
- 所有自定义维度用 **`hubsuite:` 自有 key**，不占用 `minecraft:overworld` 等原版 key
- 软依赖（运行时探测，缺失要能降级）：LuckPerms / HuskHomes / Placeholder API

> 详细代码地图见 [`HANDOFF.md`](HANDOFF.md) 的「六、关键代码地图」。

---

## 三、构建与运行

### 编译

```powershell
$j = "$env:USERPROFILE\scoop\apps\openjdk25\current"   # 必须 Java 25
$env:JAVA_HOME = $j; $env:PATH = "$j\bin;$env:PATH"
Set-Location E:\Github\ServerMod
.\gradlew.bat build --console=plain
```

### 起测试服务端

> 🔴 **端口只能改 `run\server.properties` 文件 —— `-Pport=` 是无效参数！**
> `build.gradle` 里只把 `-Dhubsuite.selftest` 接线到 `runServer` 的分叉 JVM，
> **没有接线 `port`**。传 `-Pport=25566` 它照样按 `server.properties` 起。
> （这个坑很坑：如果 `server.properties` 恰好是 25566 而你又传了 `-Pport=25566`，
> 看起来"参数生效了"，其实是文件本来就是这个值。别被骗。）

```powershell
# ① 改端口。25565 是作者自己客户端的端口，占了会把他踢下线
(Get-Content run\server.properties) -replace '^server-port=.*','server-port=25566' |
  Set-Content run\server.properties -Encoding ASCII

# ② 起服务端（无人值守自检，20 秒后开始）
.\gradlew.bat runServer --console=plain '-Dhubsuite.selftest=20'

# ③ 用完还原
(Get-Content run\server.properties) -replace '^server-port=.*','server-port=25565' |
  Set-Content run\server.properties -Encoding ASCII
```

服务端是 `online-mode=false`，机器人可直连。
自检结果看 `run\logs\latest.log` 里的 `通过 N 项，失败 M 项`。

### 清档（改了地形/生成器才需要）

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
# run\mods 与 run\server.properties 要留着
```

---

## 四、改完代码的验收标准（硬性）

1. `.\gradlew.bat build --console=plain` **通过**
2. 跑一次自检：`.\gradlew.bat runServer --console=plain '-Dhubsuite.selftest=20'`
   （端口先按上面改好）
3. 日志里 **`失败 0 项`** 且 **看门狗 0 次**（搜 `A single server tick`）
4. 涉及多世界隔离 / 维度 / 玩家数据的改动，**优先补一条自检**而不是只靠手测

> 多世界隔离这类东西**光看代码确认不了**。本项目历史上好几个"看起来做对了、
> 实际根本没生效"的 bug（成就隔离、地形种子、虚空村庄）都是自检抓出来的。

### 加自检时的注意事项

自检整体跑在 `END_SERVER_TICK` 里（**同步**）：

- ❌ 不要同步加载区块、不要大范围扫描、不要同步落盘
  → 单 tick 超 60 秒会被看门狗强杀，整个自检白跑
- ✅ 要等世界生成 → 用 `defer(...)` 跨 tick 机制
- ✅ 要读方块 → 先用 `getChunkNow(cx,cz) != null` 确认区块到 FULL
  （**不要用 `hasChunk`**，它只保证"非空"）
- ✅ 要落盘 → 传 `flush=false`，再用 `defer` 校验文件

---

## 五、编码约定

- **注释写"为什么"，不写"是什么"**。踩过的坑一定要留在注释里
  （本项目的注释密度就是这么来的，很有价值，别删）
- **禁止阻塞主线程**。要等异步结果就跨 tick 延迟，不要 `.join()` / `.get()`
- **原版行为不能改坏**。改动原版逻辑时：
  - 没登记过的维度 / 没匹配的情况 → **原样返回原版结果**
  - 加自检时顺便断言"原版行为保持不变"
- **少写日志**（作者明确要求过）：
  - 每次动作都触发的（建岛、补铺平台、切服）用 **debug**，不要 INFO
  - 调试用的临时日志和一次性排查方法，**调试完就删**
  - 启动时一次性、或异常路径的日志保留 INFO/WARN
- **加子服/岛型不写代码** —— 那是配置驱动的，别硬编码

---

## 六、环境坑（Windows / PowerShell 5.1）

| 坑 | 说明 |
|---|---|
| **没有 `pwsh`** | 只有 Windows PowerShell 5.1 |
| **`.ps1` 必须带 UTF-8 BOM** | 否则 PS 5.1 按 GBK 读，中文全乱 |
| **`-Pport=` 无效** | 见上面「起测试服务端」，端口改 `run\server.properties` |
| `gh --jq` 引号会坏 | dump JSON 到文件再 `ConvertFrom-Json` |
| `git push` 成功也 exit 1 | PS 把 git 的 stderr 当报错。看输出里有没有 `main -> main` |
| `git log --format=%B` 管道压平换行 | 提交信息写文件后用 `git commit -F` |
| heredoc `@"..."@` 吃变量 | 先写脚本文件再执行 |
| `"$sub\players"` 被当盘符 | 用 `Join-Path` |
| **别用 PS 正则批量改源码** | `(Get-Content -Raw) -replace ... \| Set-Content` 会吃掉换行、按 GBK 重写。用编辑工具逐处改 |
| 读文件乱码 | PS 5.1 显示 UTF-8 会乱码，**文件本身没问题**。要复核就用 `[System.IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)` |

---

## 七、排查手法（本项目的"高杠杆"技巧）

### 字节码验证（最有用的一招）

不要靠猜原版行为，直接看字节码：

```powershell
$javap = "$env:USERPROFILE\scoop\apps\openjdk25\current\bin\javap.exe"
$jar = "$env:USERPROFILE\.gradle\caches\fabric-loom\26.1.2\minecraft-extracted_server.jar"
& $javap -p -c -classpath $jar net.minecraft.server.level.ServerPlayer
```

本项目好几个根因都是这么定的（`ChunkMap` 在构造内部取种子、
`PlayerList.remove` 不清缓存、`BaseFireBlock` 写死只认两个维度 key）。

### 游戏内方块探测（用 RCON / 机器人时）

- `data get block` **只对方块实体有效**，普通方块报 "not a block entity"
- 普通方块用侧信道：
  ```mcfunction
  scoreboard players set #m hsprobe 0
  execute if block <x> <y> <z> <id> run scoreboard players set #m hsprobe 1
  scoreboard players get #m hsprobe     → "#m has 1" 即命中
  ```
- 未加载区块里方块指令返回 `That position is not loaded`，先 `forceload add`
- `data get entity <玩家> Pos` 在跨维度传输途中会整行失败，要重试

### 其他

- 崩溃栈：`run\crash-reports\`
- 自检进度：日志里搜 `[自检] ->`
- 数据落盘位置见 [`HANDOFF.md`](HANDOFF.md)「七、数据落盘位置」

---

## 八、发布流程

```
1. 改 gradle.properties 的 mod_version
2. 同步文档里的产物文件名（README.md / docs/**）
3. 跑一次自检确认 失败 0 项
4. git commit（信息写文件，用 git commit -F）
5. git tag -a v<版本> -m "HubSuite v<版本>"
6. git push origin main && git push origin v<版本>
7. gh release create v<版本> --title "..." --notes-file <说明.md> <jar> <sources.jar>
8. 从 GitHub **真实下载**校验：zip 有效、fabric.mod.json 的 version 对、类数对
```

**发布说明必须如实写**：哪些已验证、哪些没有、升级要不要删存档。
不要为了让 release 好看而省略"未验证"。

---

## 九、禁止事项

- ❌ 不要抢 **25565** 端口起测试服务端（会踢掉作者的客户端）
- ❌ 不要把 `build/`、`run/`、`mods/` 里的产物提交进版本库
- ❌ 不要为了"让自检变绿"而放宽断言 —— 宁可留一条红的，也不要假绿
- ❌ 不要在没有实测的情况下修改 README 里"已验证"的结论
- ❌ 不要一次性删掉别人写的大段注释（那些注释是踩坑记录，是本项目的资产）
