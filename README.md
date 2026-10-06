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
| **假人大厅** | 真 `ServerPlayer` 假人走完整入服路径，不出现在 Tab、不计在线人数；点一下弹出**箱子式服务器选择界面**（每子服一个条目，图标 + 在线人数 + PvP/飞行状态） |
| **多世界子服** | 每个子服一个**完全独立的存档**：独立规则、独立背包/经验/成就、独立世界边界 |
| **配置驱动扩展** | 新增子服 = 加一条 JSON 配置，**不写代码** |
| **空岛系统** | 空岛服 = **1 个大厅 + 2 个岛屿维度**：经典空岛用网格方格，海岛是**自然生成的海洋世界**（海洋/暖海/深海群系 + 沉船、海底废墟、珊瑚礁），岛按距离摆放。两种岛可同时拥有，归属与地形完全独立 |
| **入口分流** | 进空岛服时按"上次在哪个岛"回哪个岛；没岛先去大厅，大厅假人点开**岛型选择界面** |
| **权限分级** | LuckPerms → fabric-permissions-api → 原版 OP 三级降级；创造服屏蔽 40+ 条作弊指令 |
| **内置自检** | `/hub selftest` 跑 **182 项**验证，覆盖存档隔离、规则隔离、玩家数据隔离、账号安全、空岛生成与保护、入口分流、集成适配、指令注册 |

---

## 快速开始

```bash
# 1. 编译
./gradlew build
# 产物：build/libs/hubsuite-0.2.0.jar

# 2. 把 jar 和以下依赖放进服务端 mods/
#    hubsuite-0.2.0.jar                 （必需）
#    fabric-api-0.155.3+26.1.2.jar      （必需）
#    HuskHomes-Fabric-4.11+mc.26.1.2.jar（建议：家/传送指令）
#    placeholder-api-3.0.0+26.1.jar     （建议）
#    fabric-permissions-api-0.7.0.jar   （建议）
#    LuckPerms-Fabric-5.5.85.jar        （可选）

# 3. 启动服务端，首次会自动生成配置与 6 个独立存档
#    （大厅、生存、创造、空岛大厅、经典空岛、海岛）
# 4. 控制台验证
hub selftest
```

### 指令速览

```
/menu                 服务器选择界面（箱子 GUI）
/hub [服务器|list|where]   传送
/auth list|reset|delete|kick   账号管理（管理员）
/island [hub|classic|ocean|home|info|types|reset]   空岛功能
```

详细步骤见 **[安装部署](docs/安装部署.md)**，或直接看教程
**[01 · 十分钟跑起来](docs/教程/01-十分钟跑起来.md)**。

---

## 文档

| 文档 | 内容 |
|---|---|
| **[📚 教程合集](docs/教程/README.md)** | **上手向实操教程：十分钟跑起来 / 加子服 / 配空岛 / 配任务 / 权限 / 写对话框 / 排查 / 开发调试** |
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
├─ src/main/java/cn/dreamgary/hubsuite/   源码（86 个 Java 文件 / 17 个 Mixin）
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

本项目采用 [MIT 许可证](LICENSE)。

```
MIT License

Copyright (c) 2026 DreamGary

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

简单说：你可以自由使用、修改、分发（包括商用），只需保留版权声明；
软件按"现状"提供，作者不承担由此产生的责任。
