# HubSuite

> Minecraft **26.1.2** 的**纯服务端** Fabric 模组：对话框注册/登录 + 假人大厅 +
> 生存/创造/空岛三个独立子服，并对 HuskHomes 与 Text Placeholder API 做了适配。
>
> 客户端使用原版即可进服，**不需要**安装任何模组。

---

## 特性

| 模块 | 说明 |
|---|---|
| **对话框登录** | 26.1 原版对话框表单（`TextInput` + `CustomAll` 静默回传），密码**不进聊天、不进命令、不落日志**；PBKDF2-HMAC-SHA256 加盐 + SQLite |
| **假人大厅** | 真 `ServerPlayer` 假人走完整入服路径，不出现在 Tab、不计在线人数；右键弹确认框，左键直接进服 |
| **多世界子服** | 每个子服一个**完全独立的存档**：独立规则、独立背包/经验/成就、独立世界边界 |
| **配置驱动扩展** | 新增子服 = 加一条 JSON 配置，**不写代码** |
| **空岛系统** | plot 网格分配、数据驱动的岛型（classic / ocean）、方格保护、岛员邀请 |
| **权限分级** | LuckPerms → fabric-permissions-api → 原版 OP 三级降级；创造服屏蔽 40+ 条作弊指令 |
| **内置自检** | `/hub selftest` 跑 49 项验证，覆盖隔离机制与集成状态 |

---

## 快速开始

```bash
# 1. 编译
./gradlew build
# 产物：build/libs/hubsuite-0.1.0.jar

# 2. 把 jar 和以下依赖放进服务端 mods/
#    hubsuite-0.1.0.jar                 （必需）
#    fabric-api-0.155.3+26.1.2.jar      （必需）
#    HuskHomes-Fabric-4.11+mc.26.1.2.jar（建议：家/传送指令）
#    placeholder-api-3.0.0+26.1.jar     （建议）
#    fabric-permissions-api-0.7.0.jar   （建议）
#    LuckPerms-Fabric-5.5.85.jar        （可选）

# 3. 启动服务端，首次会自动生成配置与 4 个独立存档
# 4. 控制台验证
hub selftest
```

详细步骤见 **[安装部署](docs/安装部署.md)**。

---

## 文档

| 文档 | 内容 |
|---|---|
| [安装部署](docs/安装部署.md) | 环境要求、依赖清单、首次启动、备份与迁移、常见问题 |
| [配置说明](docs/配置说明.md) | `config.json` 每个字段的含义与默认值 |
| [命令与权限](docs/命令与权限.md) | 全部命令、权限节点、LuckPerms 配置示例 |
| [架构与扩展](docs/架构与扩展.md) | 隔离原理、Mixin 清单、如何加子服/岛型/对话框 |
| [开发进度](PROGRESS.md) | 分阶段完成情况与踩坑记录 |

---

## 环境

| 项目 | 版本 |
|---|---|
| Minecraft | **26.1.2**（严格锁定） |
| Fabric Loader | 0.19.5+ |
| Fabric API | 0.155.3+26.1.2 |
| Java | **25** |
| 服务端 | Fabric dedicated server |

> ⚠️ 26.1 起 Minecraft 官方不再混淆代码，Fabric 取消了 Yarn/Intermediary 映射。
> **1.21.11 及更早版本的模组在 26.1.2 上无法加载**。

---

## 目录结构

```
MinecraftSeverMod/
├─ src/main/java/cn/dreamgary/hubsuite/   源码（55 个类）
├─ src/main/resources/                    fabric.mod.json / mixin 配置 / 图标
├─ docs/                                  中文文档
├─ mods/                                  依赖 jar（归档，不入版本库）
├─ run/                                   开发用服务端（含 eula、rcon 配置）
├─ scripts/dev-server.sh                  无人值守起服 + 跑命令的测试脚本
├─ PROGRESS.md                            开发进度与踩坑记录
└─ build.gradle / gradle.properties       构建配置
```

---

## 开发

```bash
# 编译
./gradlew build

# 无人值守跑一次服务端并执行命令（自动等就绪、执行、关服）
./scripts/dev-server.sh "hub info" "hub selftest"

# 直接起服务端（会一直运行）
./gradlew runServer
```

**改完代码请务必跑一次 `hub selftest`** —— 多世界隔离这类东西光看代码确认不了。

---

## 许可

All Rights Reserved.
