# 05 · 权限与 LuckPerms

---

## 三级降级

模组的权限判定按这个顺序：

```
1. LuckPerms（通过 fabric-permissions-api 反射桥接）
2. 原版 OP 等级
3. 默认拒绝
```

**没装 LuckPerms 也能用** —— 退化成"OP 等级 ≥ 2 就是管理员"。
装了 LuckPerms 后节点才生效。

启动时控制台会打一行告诉你当前用的是哪个后端：

```
权限后端：LuckPerms（经 fabric-permissions-api）
```

用 `/hub perms` 可以看到**你自己**当前的判定结果（排查权限问题的最快方式）。

---

## 权限节点

| 节点 | 含义 |
|---|---|
| `hubsuite.admin` | 全部管理指令（`/hub selftest`、`/auth ...`） |
| `hubsuite.build.creative` | 在创造服绕过作弊指令黑名单 |
| `hubsuite.island.admin` | 空岛管理（预留，当前无对应指令） |

> ⚠️ **已知限制**：`/auth` 与 `/hub selftest` 目前只检查**原版 OP 等级 2**，
> 没有走 `hubsuite.admin` 节点。也就是说：
> - 被 LuckPerms 显式拒绝 `hubsuite.admin`、但仍有 OP 等级 2 的人，**照样能用**这些指令
> - 被授予 `hubsuite.admin` 的非 OP，**用不了**
>
> 想严格控制的话，**别给 OP 等级**，或者等这个限制被修掉。

---

## 常用配法

### 只给一个人建站权限

```
/lp user 某人 permission set hubsuite.admin true
```

### 建一个"服主"组

```
/lp creategroup owner
/lp group owner permission set hubsuite.admin true
/lp group owner permission set hubsuite.build.creative true
/lp user 某人 parent add owner
```

### 默认组不给任何模组权限

```
/lp group default permission set hubsuite.admin false
```

---

## 创造服的作弊指令黑名单

模组会拦住一批作弊指令（`/give`、`/setblock`、`/effect` 等 40+ 条），
**但只在玩家没有建造权限时才拦**：

```
拦：没有 hubsuite.build.creative 的人
放：有该节点的人，或 OP 等级 ≥ 2 的人
```

**默认配置下这个功能基本不生效** —— 因为能执行 `/give` 的人本身就有等级 2，
必然也通过了建造权限判定。要让它真正生效，得显式设置：

```
/lp group default permission set hubsuite.build.creative false
```

> ⚠️ **绕过方式**：黑名单只取命令的第一个词，所以
> `/execute run give @s diamond 64`、`/function ...` 这类**包装指令能绕过**。
> 这是已知缺口，别把它当成可靠的安全边界。

---

## 和 HuskHomes 的关系

`huskhomesTeleport` 控制某个子服**是否允许 HuskHomes 传送**：

```json
{ "id": "survival", "huskhomesTeleport": true }
{ "id": "creative", "huskhomesTeleport": false }
```

设成 `false` 后，在那个子服里用 `/home`、`/tpa` 会被模组拦下。
HuskHomes 自己的权限节点（`huskhomes.*`）照常工作。

---

## 排查

**「我明明给了权限但还是说没权限」**

1. 跑 `/hub perms`，看节点判定结果和当前后端
2. 如果后端显示"原版 OP"而不是 LuckPerms，说明反射桥接没成功 ——
   检查 `fabric-permissions-api` 和 `LuckPerms-Fabric` 是不是都在 `mods/` 里
3. LuckPerms 改完权限后让玩家重连一次（部分缓存要重连才刷新）

**「管理员指令用不了」**

`/hub selftest`、`/auth` 只认 OP 等级 2。控制台执行 `op <名字>` 即可。

---

## 下一步

- [07 · 排查问题](07-排查问题.md)
