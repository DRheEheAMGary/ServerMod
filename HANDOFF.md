# HubSuite 交接说明（给下一个会话）

> 本文件是为 **whale_craft 插件生效需要重启 DSH** 而写的。
> 重启会结束上一个会话，所以把状态落在这里。

## 一、怎么自己进服务器测试（whale_craft）

已装好：`whale_craft@0.2.0`（DSH 插件，用 mineflayer 让 agent 真的进 MC 服务器）。

- 安装位置：`C:\Users\Gary\.dsh\profiles\web\node_modules\whale_craft`
- 已在 `profiles/web/package.json` 的 `dsh.profile.bundles` 里
- `mineflayer` 也已装好

**用法（重启 DSH 之后）**：

1. **新建对话**，模式选「MC模式」（只玩 MC）或「MC+模式」（还要普通工具 —— 调试代码就用这个）
2. **必须选中工作区** —— 建议就选 `E:\Github\ServerMod`
   （`.whale-craft/` 的记忆与提示词建在工作区里；没选工作区时服务端不把会话当 MC 模式）
3. 说「进 127.0.0.1:25566 服务器」

**注意**：机器人用离线账号登录。测试服务端要设 `online-mode=false`
（`scripts/selftest-run.ps1` / `gradlew runServer` 默认就是离线模式）。

## 二、起测试服务端

**不要用 25565**（那是用户客户端/自己服务器的端口，抢了会把人踢下线）。

```powershell
# JAVA_HOME 必须指向 Java 25
$j = "$env:USERPROFILE\scoop\apps\openjdk25\current"
$env:JAVA_HOME = $j; $env:PATH = "$j\bin;$env:PATH"

# 跑无人值守自检（15 秒后开始，端口 25566）
.\gradlew.bat runServer --console=plain '-Dhubsuite.selftest=15' '-Pport=25566'
```

自检结果看 `run/logs/latest.log` 里的 `通过 N 项，失败 M 项`。

**清档**（要重新生成地形时必须做）：

```powershell
foreach ($t in @("run\world","run\hubsuite_lobby","run\hubsuite_survival","run\hubsuite_creative",
                 "run\hubsuite_survival_nether","run\hubsuite_survival_end",
                 "run\hubsuite_creative_nether","run\hubsuite_creative_end",
                 "run\hubsuite_skyblock","run\hubsuite_skyblock_hub","run\hubsuite_skyblock_classic",
                 "run\hubsuite_skyblock_ocean","run\config","run\logs","run\crash-reports")) {
  if (Test-Path $t) { Remove-Item $t -Recurse -Force -ErrorAction SilentlyContinue }
}
Get-ChildItem run -Recurse -Filter "session.lock" -EA SilentlyContinue | Remove-Item -Force
# 注意：run\mods 和 run\server.properties 要留着
```

## 三、环境坑（踩过的）

| 坑 | 说明 |
|---|---|
| `pwsh` 不存在 | 只有 Windows PowerShell 5.1。后台任务用 `& script.ps1` |
| `.ps1` 必须带 UTF-8 BOM | 否则 PS 5.1 按 GBK 读中文会乱 |
| `gh --jq` 引号易坏 | 改成 dump JSON 到文件再 `ConvertFrom-Json` |
| `git log --format=%B` 管道会压平换行 | 提交信息写文件后用 `git commit -F` |
| heredoc `@"..."@` 会吃掉变量 | 先写脚本文件再执行 |
| `Get-ChildItem run\...` 里 `$sub:` 会被当盘符 | 用 `Join-Path` |

## 四、当前状态（截至 0.2.4）

- 最新发布：**v0.2.4**，`main` 与远端同步，工作区干净
- 最新提交：`ec08157 fix(portal): 点火点不出传送门`
- 自检：**192 项通过 / 0 失败 / 看门狗 0 次**

### 已修并**本机验证**过的

| 问题 | 证据 |
|---|---|
| 生存/创造是同一张地图 | `[通过] 地形不同：6/6 个采样点高度不一致` |
| 虚空里长出村庄 | `[通过] 虚空维度里没有任何结构集（4 个）` |
| 传送门点不着 | `[通过] 火能在本模组的维度里点燃传送门（4 个维度）` |
| 退出服务器丢成就 | `[通过] 重新登录，成就仍在` |
| 切服不隔离成就 | `[通过] 切服隔离成立：生存服的成就没出现在创造服` |
| 缓存不放掉 | `[通过] 切服后成就/统计缓存已放掉` |
| 传送门改道 | `[通过] 进/出各方向都指向本子服自己的维度（8 项映射）` |

### **未验证 / 待确认**

1. **生存服出生点仍可能卡地里** —— `tickPendingSpawns` 的两处复检漏洞已修
   （改成无条件写回 + 校验出生点所在区块），但**没在真实登录里复现验证**。
   用 whale_craft 进服实地看最直接。
2. **传送门实际走一趟** —— 自检验的是"能不能点火"和"改道映射"，
   **没有真的让玩家钻进传送门**。建议实测：生存服建门 → 进 → 应在
   `hubsuite:survival_nether` → 回来 → 应回 `hubsuite:server_survival`。
3. **下界/末地的地形** —— 生成器参数取自原版注册表，但没实地看过。

### 仍未解决

- 26.1.2 的 **RCON 只在极窄时序下回包**，无人值守取命令输出不可靠
  （自带 `-Dhubsuite.selftest=` 是替代方案）。

## 五、关键代码位置

| 文件 | 作用 |
|---|---|
| `world/LevelSeeds.java` + `mixin/ServerLevelSeedMixin.java` | 每个维度自己的地形种子（登记必须**赶在** `new ServerLevel` 之前） |
| `world/PortalLinks.java` + `mixin/PortalBlockDestinationMixin.java` | 传送门去哪个维度（双向登记） |
| `mixin/BaseFireBlockPortalMixin.java` | 让火能在自有维度里点火（原版只认 `overworld`/`the_nether`） |
| `world/AuxDataRouter.java` + `mixin/PlayerListAuxDataMixin.java` | 成就/统计按子服隔离（**必须放缓存**，路径烧死在 final 字段里） |
| `world/SubServer.java` | 建维度、出生点解析 `resolveSpawn`/`sanitizeSpawn`、待铺平台 |
| `world/SelfTest.java` | 全部自检（加检查时注意别做同步重活，会被看门狗杀） |

## 六、加自检的注意事项

自检整体跑在 `END_SERVER_TICK` 里（**同步**）。任何一步做重活（同步落盘、
同步加载区块、大范围扫描）都会让单 tick 超 60 秒被看门狗强杀 ——
以前 `checkRulesPersistence` 就是这么把整个自检搞崩的。

- 要等世界生成 → 用 `defer(...)` 跨 tick 机制
- 要读方块 → 先用 `getChunkNow(cx,cz) != null` 确认区块到 FULL
  （**不要用 `hasChunk`** —— 它只保证"非空"，不代表到 FULL）
- 要落盘 → 传 `flush=false`，然后 defer 去校验文件
