# HubSuite

> Minecraft **26.1.2** 的**纯服务端** Fabric 模组：把**一个服务端**变成
> 「大厅 + 多个互相独立的子服」（生存 / 创造 / 空岛），带对话框注册登录、
> 假人大厅与内置任务系统，并对 HuskHomes、Text Placeholder API、JEI 做了适配。
>
> 客户端使用**原版**即可进服，**不需要**安装任何模组。

---

## AI 参与声明

本项目的**代码与文档由人类作者与 AI Agent 协作完成**（使用模型：DeepSeek V4.1 Flash）。如实说明：

**AI 参与的部分**

- 大部分 Java 实现、Mixin、自检用例与中文文档，由 AI Agent 在人类作者指导下编写
- 根因排查：多个 bug 是通过阅读**字节码**定位的（例如 `ChunkMap` 在 `ServerLevel` 构造内部就取走地形种子、
  `BaseFireBlock` 写死只认两个原版维度 key、`PlayerList.remove` 不清成就缓存）

**人类作者负责的部分**

- 需求定义、架构决策、验收标准
- **亲手在真实服务器上实测**每个修复 —— 包括建传送门走一趟、切服看成就、删档看地形这类 AI 无法自行确认的行为
- 多次纠正 AI 的错误方向（例如「虚空世界不能换成 `the_void` 群系，否则大厅不刷生物了」）

**关于质量**

- 项目带**内置自检**（`/hub selftest`，当前 221 项），覆盖存档隔离、规则隔离、玩家数据隔离、
  账号安全、空岛生成与保护、入口分流、集成适配、指令注册等
- 但 **AI 生成的代码不能只靠自检背书**。自检是「AI 自己出的题」，
  历史上出现过「自检全绿、实机仍然不对」的情况（自检覆盖不到的路径）
- 因此：**每个版本都有人类实测确认**，未实测的项在发布说明里明确标注为「未验证」

**给使用者的话**

- 请把本项目当作「人类作者对结果负责的协作产物」。发现问题请提 issue，作者会实测复现后再修
- 想继续用 AI 开发本项目，请先读 [`AGENT.md`](AGENT.md)（工作约定与踩坑记录）

---

## 特性

| 模块 | 说明 |
|---|---|
| **对话框登录** | 26.1 原版对话框表单（`TextInput` + `CustomAll` 静默回传），密码**不进聊天、不进命令、不进日志**；PBKDF2-HMAC-SHA256 加盐 + SQLite |
| **假人大厅** | 真 `ServerPlayer` 假人走完整入服路径，不出现在 Tab、不计在线人数；点一下弹出**箱子式服务器选择界面** |
| **多世界子服** | 每个子服一个**完全独立的存档**：独立规则、独立背包/经验/成就、独立世界边界 |
| **配置驱动扩展** | 新增子服 = 加一条 JSON 配置，**不写代码** |
| **独立下界/末地** | 每个子服拥有自己的下界与末地（传送门自动改道），不串服 |
| **空岛系统** | 空岛服 = **1 个大厅 + 2 个岛屿维度**：经典空岛用网格方格，海岛是**自然生成的海洋世界**；两种岛可同时拥有，归属与地形完全独立；**岛屿保护已删除** |
| **岛屿下界/末地** | 经典空岛 = 虚空式下界（门下有下界岩小岛、虚空里有要塞与猪灵堡垒），海岛 = **岩浆海**下界；两座末地都是原版 |
| **自然岛** | 空岛服/海岛服里有带**宝箱 + 刷怪笼 + 村庄房屋**的自然小岛，位置/物资/生物全在配置里 |
| **海岛渔获** | 按维度挂渔获表：稀有度分级（common → legendary）+ **按群系分池**（暖海沙子 / 冷海砂砾 / 冻洋冰块） |
| **岩浆钓鱼 / 副手钓饵** | **熔岩钓竿**才能在岩浆里钓，岩浆有独立渔获表（按**下界群系**分化）；副手放**钓饵**可加速咬钩（+10% / +25% / +45%） |
| **自定义物品** | 8 个自定义物品（地狱线、两系钓饵、熔岩钓竿）走 **Polymer**：服务端是独立 id 的真物品，原版客户端也能正常看到 |
| **内置任务** | 里程碑 / 物资交付 / 每日三类任务，配置驱动；进度按玩家存 |
| **权限分级** | LuckPerms → fabric-permissions-api → 原版 OP 三级降级；创造服屏蔽 40+ 条作弊指令 |
| **内置自检** | `/hub selftest` 跑 **221 项**验证，是项目的主要回归门 |

---

## 环境

| 项目 | 版本 |
|---|---|
| Minecraft | **26.1.2**（严格锁定） |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.155.3+26.1.2 |
| Java | **25** |
| Polymer（服务端必需） | 0.16.5+26.1.2（自定义物品靠它映射给原版客户端） |
| 服务端 | Fabric dedicated server |

> ⚠️ 26.1 起 Minecraft 官方不再混淆代码，Fabric 取消了 Yarn / Intermediary 映射。
> **1.21.11 及更早版本的模组在 26.1.2 上无法加载**。

---

## 快速开始

```bash
# 1. 编译（需要 JDK 25）
./gradlew build
# 产物：build/libs/hubsuite-0.4.0-SNAPSHOT.jar
```

把 jar 和依赖放进服务端 `mods/`：

| jar | 必需 | 用途 |
|---|---|---|
| `hubsuite-0.4.0-SNAPSHOT.jar` | 必需 | 本模组 |
| `fabric-api-0.155.3+26.1.2.jar` | 必需 | Fabric API |
| `polymer-bundled-0.16.5+26.1.2.jar` | **必需** | 自定义物品的客户端映射；**缺了模组不会加载** |
| `HuskHomes-Fabric-4.11+mc.26.1.2.jar` | 建议 | 家 / 传送点 / Tpa / Warp 指令 |
| `placeholder-api-3.0.0+26.1.jar` | 建议 | 让其它模组读到 `%hubsuite_...%` |
| `fabric-permissions-api-0.7.0.jar` | 建议 | 权限 API（LuckPerms 的前置） |
| `LuckPerms-Fabric-5.5.85.jar` | 可选 | 权限组管理 |
| `jei-26.1.2-fabric-29.47.0.109.jar` | 可选 | 装在**服务端**可让客户端 JEI 用配方转移 / 作弊给予；自定义配方无论装不装都会出现在客户端 JEI 的配方页（走原版配方同步） |

> HuskHomes 必须用 **Fabric 版 4.11+ 的 26.1 构建**。旧版（如 4.6）用已废弃的 intermediary 名，
> 在 26.1.2 上会直接加载失败，且版本检查拦不住。

```text
# 3. 首次启动，自动生成配置与独立存档
#    config/hubsuite/ 下：config.json、accounts.db、last-locations.json、rules/、skyblock/
#    服务端根目录下：hubsuite_lobby/、hubsuite_survival/、hubsuite_creative/、hubsuite_skyblock/
# 4. 控制台验证（无需客户端）
hub selftest
# 全部通过会打印：通过 221 项，失败 0 项。
```

> `server.properties` 里不要写任何非 ASCII 字符（Java Properties 按 ISO-8859-1 读，会启动失败）。
> 建议：`online-mode=false`、`gamemode=adventure`、`allow-flight=true`、`spawn-protection=0`。

---

## 命令速览

```text
/menu                       打开服务器选择界面（箱子 GUI，与大厅假人同一界面）
/hub                        回大厅
/hub <子服>                 直达某个子服，如 /hub survival
/hub list                   列出所有子服与在线人数
/hub where                  我现在在哪个维度、坐标多少
/hub selftest               运行 221 项内置自检（管理/控制台）

/island                     空岛帮助（需在空岛服相关维度）
/island menu                打开选岛界面
/island hub                 回空岛服大厅
/island classic             去经典空岛（没有就建一座）
/island ocean               去海岛（没有就建一座）
/island home                回上次那种岛
/island info                我的岛信息
/island types               列出所有岛型
/island reset [类型]        重置我的岛（清归属重建，谨慎）

/register <密码> <确认密码>  认证兜底注册
/login <密码>               认证兜底登录

/auth list                  已注册账号数（管理）
/auth reset <玩家> <新密码>  重置密码并解锁（管理）
/auth delete <玩家>         删除账号（管理）
/auth kick <玩家>           让某玩家重新登录（管理）
```

权限节点只有三个：`hubsuite.admin`（管理命令）、`hubsuite.build.creative`（在被屏蔽作弊指令的子服放行）、
`hubsuite.island.admin`（空岛管理，预留）。详见 [命令与权限](docs/命令与权限.md)。

---

## 文档

| 文档 | 内容 |
|---|---|
| [安装部署](docs/安装部署.md) | 环境要求、依赖清单、首次启动、备份与迁移、常见问题 |
| [配置说明](docs/配置说明.md) | `config.json` 每个字段的含义与默认值 |
| [命令与权限](docs/命令与权限.md) | 全部命令、权限节点、LuckPerms 配置示例 |
| [架构与扩展](docs/架构与扩展.md) | 隔离原理、Mixin 清单、如何加子服 / 岛型 / 任务 / 对话框 |
| [自定义物品](docs/自定义物品.md) | 熔岩钓竿 / 钓饵 / 渔桶：清单、合成表、渔桶用法、资源包怎么写 |
| [教程合集](docs/教程/README.md) | 上手向实操教程：十分钟跑起来 / 加子服 / 配空岛 / 配任务 / 权限 / 写对话框 / 排查 / 开发调试 |

---

## 目录结构

```text
MinecraftSeverMod/
├─ src/main/java/cn/dreamgary/hubsuite/   源码（93 个 Java 文件 / 22 个 Mixin）
├─ src/main/resources/                    fabric.mod.json / mixin 配置 / 图标
├─ docs/                                  中文文档
├─ mods/                                  依赖 jar（归档，不入版本库）
├─ run/                                   开发用服务端（含 eula、rcon 配置）
├─ scripts/dev-server.sh                  无人值守起服 + 跑命令的测试脚本
├─ AGENT.md                               给 AI Agent 的工作约定
└─ build.gradle / gradle.properties       构建配置
```

---

## 开发

```bash
# 编译
./gradlew build

# 无人值守启动服务端并跑命令（脚本自动等就绪、执行、关服）
./scripts/dev-server.sh "hub selftest"

# 直接起服务端（会一直运行）
./gradlew runServer
```

**改完代码请务必跑一次 `hub selftest`** —— 多世界隔离这类东西光看代码确认不了。

> ⚠️ 起测试服务端时**改 `run/server.properties` 的 `server-port`**，
> 别用 `-Pport=`（`build.gradle` 没接线，传了也无效，会在 25565 上起、把你自己的客户端踢下线）。
>
> 无人在场时可以用 `./gradlew runServer -Dhubsuite.selftest=20` 让服务端起来就自检
> （该属性由 `build.gradle` 转发到 runServer 的分叉 JVM）。

用 AI 协作开发请先读 [AGENT.md](AGENT.md)（工作约定、验收标准与踩坑记录）。

---

## 许可

本项目采用 [MIT 许可证](LICENSE)。

简单说：你可以自由使用、修改、分发（包括商用），只需保留版权声明；
软件按「现状」提供，作者不承担由此产生的责任。
