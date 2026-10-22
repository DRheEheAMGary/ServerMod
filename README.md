# HubSuite

> 把**一台** Minecraft 服务端，变成「大厅 + 多个互相独立的子服」的 Fabric 模组。

一个服务端进程，跑出大厅、生存、创造、空岛（+ 海岛）几个**完全隔离**的世界；自带账号登录、任务系统与钓鱼玩法。

- 服务器端模组 + 一份配套的客户端模组（自定义物品与界面需要）
- 支持 **Minecraft 26.1.2** / Fabric / Java 25
- 开源协议：[MIT](LICENSE)

---

## 环境要求

| 项目 | 要求 |
|---|---|
| Minecraft | **26.1.2** |
| 服务端 | Fabric dedicated server（不支持 Paper / Spigot / Forge / NeoForge） |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.155.3+26.1.2 |
| Java | **25** |

> ⚠️ 26.1 起 Minecraft 不再混淆代码，Fabric 取消了 Yarn 映射，**1.21.11 及更早版本的模组在 26.1.2 上无法加载**。

## 安装

1. 从 [Releases](https://github.com/DRheEheAMServer/HubSuite/releases) 下载 `hubsuite-<版本>.jar`，放进服务端 `mods/`；
2. 同一份 Release 里的 `hubsuite-client-<版本>.jar`，放进**每个玩家**的客户端 `mods/`。

**客户端模组是必需的。** 本模组加入了自定义物品（地狱线、渔桶、钓竿部件等），玩家客户端没装对应模组会因物品注册表对不上而**进不了服**。

其余依赖全是**软依赖**，缺了不影响启动，只少对应功能：

| jar | 作用 |
|---|---|
| HuskHomes（Fabric 版 4.11+，26.1.2 构建） | 家 / 传送点 / Tpa / Warp |
| Placeholder API | 让其它模组读到 `%hubsuite_...%` 占位符 |
| Fabric Permissions API + LuckPerms | 权限分级 |
| JEI | 配方查看、作弊给予 |

> 🔴 HuskHomes 必须用 **Fabric 版、版本号带 26.1.2** 的构建，旧版在 26.1.2 上会直接加载失败。

首次启动会自动生成配置与各子服存档。控制台执行 `hub selftest` 验证安装是否正常。

`server.properties` 里不要写任何非 ASCII 字符（Java 按 ISO-8859-1 读，中文注释会导致启动失败）。建议 `online-mode=false`、`gamemode=adventure`、`allow-flight=true`、`spawn-protection=0`。

---

## 功能

### 子服与世界隔离

- 每个子服一个**完全独立的存档**：地形、背包、经验、成就、统计、游戏规则互不影响
- **新增子服 = 加一条 JSON 配置，不用写代码**
- 每个子服拥有自己的下界与末地，传送门自动改道，不串服
- 独立下界/末地、虚空式空岛下界、岩浆海下界

### 大厅与账号

- 假人大厅：真 `ServerPlayer` 走完整入服路径，不出现在 Tab、不计在线人数
- 点假人弹出**箱子式服务器选择界面**
- 26.1 原版对话框注册登录，密码**不进聊天、不进命令、不进日志**

### 空岛与海岛

- 空岛服 = 1 个大厅 + 2 个岛屿维度：经典空岛（网格方格）与海岛（自然生成的海洋世界），可同时拥有
- 自然小岛：带宝箱、刷怪笼、村庄房屋，位置与物资全在配置里
- 每个岛型有独立的岛型命令：`/island classic`、`/island ocean`

### 钓鱼与钓竿

- 海岛渔获按维度挂表：稀有度分级（common → legendary）+ **按群系分池**
- **岩浆钓鱼**：需要特殊钩子，线还要耐烧灼，否则断线概率 ×10；岩浆有独立渔获表
- **副手钓饵**可加速咬钩（+10% / +25% / +45%），钓饵由物品 tag 定义
- **钓竿四层装配**：杆 / 线 / 钩 / 升级，在装配台上组装，可用铁砧升级、加青金石附魔，会磨损断线

### 任务与权限

- 内置任务：里程碑 / 物资交付 / 每日三类，配置驱动，进度按玩家存
- 权限三级降级：LuckPerms → fabric-permissions-api → 原版 OP；创造服屏蔽 40+ 条作弊指令

---

## 常用命令

```text
/menu                       打开服务器选择界面
/hub                        回大厅
/hub <子服>                 直达某个子服
/hub list                   列出所有子服与在线人数
/hub where                  我现在在哪个维度、坐标多少
/hub selftest               运行内置自检（管理/控制台）

/island menu                打开选岛界面
/island hub                 回空岛服大厅
/island classic             去经典空岛
/island ocean               去海岛
/island home                回上次那种岛
/island info                我的岛信息
/island reset [类型]        重置我的岛（清归属重建，谨慎）

/register <密码> <确认密码>  认证兜底注册
/login <密码>               认证兜底登录
```

权限节点只有三个：`hubsuite.admin`、`hubsuite.build.creative`、`hubsuite.island.admin`。

---

## 文档

| 文档 | 内容 |
|---|---|
| [安装部署](docs/安装部署.md) | 环境要求、依赖清单、首次启动、备份与迁移、常见问题 |
| [配置说明](docs/配置说明.md) | `config.json` 每个字段的含义与默认值 |
| [命令与权限](docs/命令与权限.md) | 全部命令、权限节点、LuckPerms 配置示例 |
| [架构与扩展](docs/架构与扩展.md) | 隔离原理、如何加子服 / 岛型 / 任务 / 对话框 |
| [自定义物品](docs/自定义物品.md) | 钓饵 / 渔桶 / 钓竿部件：清单、合成表、用法 |
| [路线图](docs/路线图.md) | 版本里程碑与拍板项 |
| [教程合集](docs/教程/README.md) | 十分钟跑起来 / 加子服 / 配空岛 / 配任务 / 权限 / 排查 |

面向玩家的服务器介绍与玩法说明见 **[mc.dreamgary.cn](https://mc.dreamgary.cn)**；进服地址 `play.mc.dreamgary.cn`。

---

## 开发

```bash
./gradlew build                                  # 编译
./scripts/dev-server.sh "hub selftest"           # 起服并跑自检
```

改完代码请务必跑一次 `hub selftest` —— 多世界隔离这类行为光看代码确认不了。

用 AI 协作开发请先读 [AGENT.md](AGENT.md)（工作约定、验收标准与踩坑记录）。

---

## 关于

本项目的代码与文档由人类作者与 AI Agent 协作完成。AI 生成的部分以**人类的实机验收**为准，
每个版本都经真实服务器测试；自检（`/hub selftest`）是回归门，但不作为唯一依据。

## 许可

[MIT 许可证](LICENSE)。可自由使用、修改、分发（含商用），只需保留版权声明。
