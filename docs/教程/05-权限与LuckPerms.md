# 05 · 权限与 LuckPerms

目标：搞清三个权限节点、三级降级，以及常见配法。

---

## 1. 三级降级

```text
1. LuckPerms（经 fabric-permissions-api 反射桥接）
2. 原版 OP 权限等级
3. 默认放行（控制台 / 无玩家场景）
```

没装 LuckPerms 也能用，退化成「OP 等级 >= 2 就是管理员」。
启动时控制台会打印当前后端。

---

## 2. 权限节点

| 节点 | 含义 |
|---|---|
| `hubsuite.admin` | 全部管理命令 |
| `hubsuite.build.creative` | 在被屏蔽作弊指令的子服里放行 |
| `hubsuite.island.admin` | 空岛管理（预留） |

---

## 3. 已知限制

`/auth` 与 `/hub selftest` 的判定用的是**原版 OP 等级 2**，**不走 `hubsuite.admin` 节点** ——
装了 LuckPerms 也只认 OP。给管理员 OP 最直接。

---

## 4. 常用 LuckPerms 配法

```text
/lp user 某人 permission set hubsuite.admin true

/lp creategroup owner
/lp group owner permission set hubsuite.admin true
/lp group owner permission set hubsuite.build.creative true
/lp user 某人 parent add owner

/lp group default permission set hubsuite.admin false
```

改完让玩家**重连**（部分缓存要重连刷新）。

---

## 5. 创造服作弊指令黑名单

`CommandGuard` 会拦下 `/give`、`/setblock`、`/effect` 等 40+ 条指令 ——
**只在玩家没有建造权限时才拦**。有 `hubsuite.build.creative` 或 OP 等级 >= 2 的人放行。

> ⚠️ 默认配置下基本不生效（能执行 `/give` 的人本身就有等级 2）；
> 而且它**不是可靠的安全边界**：只取命令第一个词，
> `/execute run give @s diamond 64`、`/function ...` 能绕过。别拿它当防作弊。

---

## 6. 与 HuskHomes 的关系

`servers[].huskhomesTeleport` 控制某子服是否允许 HuskHomes 传送。
设 `false` 后在该子服里 `/home` / `/tpa` 会被拦，HuskHomes 自己的 `huskhomes.*` 节点照常工作。

---

## 7. 排查

1. 看启动日志打印的**权限后端**；
2. 确认 `fabric-permissions-api` 与 `LuckPerms-Fabric` 都在 `mods/`；
3. 改完权限让玩家重连；
4. 管理员指令只认 OP 等级 2 —— 控制台 `op <名字>`。
