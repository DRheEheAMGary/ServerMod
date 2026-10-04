package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * 权限查询的统一入口，三级降级：
 *
 * <ol>
 *   <li><b>fabric-permissions-api</b>（LuckPerms 通过它生效）—— 首选。
 *       用反射调用（{@code Permissions.check(Entity, String, boolean)}），
 *       这样它只是"可选的软依赖"，没装也不影响启动；</li>
 *   <li>没装权限 API 时回落<b>原版 OP 权限等级</b>；</li>
 *   <li>控制台/无玩家场景直接放行。</li>
 * </ol>
 *
 * <p>权限节点：
 * <ul>
 *   <li>{@code hubsuite.admin} —— 全部管理命令</li>
 *   <li>{@code hubsuite.build.creative} —— 创造服允许使用原版作弊指令</li>
 *   <li>{@code hubsuite.island.admin} —— 空岛管理</li>
 * </ul>
 */
public final class PermissionService {

    public static final String NODE_ADMIN = "hubsuite.admin";
    public static final String NODE_BUILD_CREATIVE = "hubsuite.build.creative";
    public static final String NODE_ISLAND_ADMIN = "hubsuite.island.admin";

    private final Method checkWithDefault;
    private final Method checkSimple;

    public PermissionService() {
        Method withDefault = null;
        Method simple = null;
        if (FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0")) {
            try {
                Class<?> permissions = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
                withDefault = permissions.getMethod("check",
                        net.minecraft.world.entity.Entity.class, String.class, boolean.class);
                simple = permissions.getMethod("check",
                        net.minecraft.world.entity.Entity.class, String.class);
            } catch (Throwable t) {
                HubSuite.logger().debug("反射接入 fabric-permissions-api 失败：{}", t.toString());
                withDefault = null;
                simple = null;
            }
        }
        this.checkWithDefault = withDefault;
        this.checkSimple = simple;
    }

    public String backendName() {
        if (checkWithDefault != null) {
            return FabricLoader.getInstance().isModLoaded("luckperms")
                    ? "LuckPerms（经 fabric-permissions-api）"
                    : "fabric-permissions-api";
        }
        if (FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0")) {
            return "fabric-permissions-api（接口未就绪，回落原版 OP）";
        }
        return "原版 OP 权限等级";
    }

    public boolean hasAdminPermission(ServerPlayer player) {
        return check(player, NODE_ADMIN, 2);
    }

    public boolean hasBuildPermission(ServerPlayer player) {
        return check(player, NODE_BUILD_CREATIVE, 2);
    }

    public boolean hasIslandAdmin(ServerPlayer player) {
        return check(player, NODE_ISLAND_ADMIN, 2);
    }

    private boolean check(ServerPlayer player, String node, int fallbackLevel) {
        if (player == null) {
            return false;
        }
        if (checkWithDefault != null) {
            try {
                Object result = checkWithDefault.invoke(null, player, node, hubsuite$levelCheck(player, fallbackLevel));
                if (result instanceof Boolean b) {
                    return b;
                }
            } catch (Throwable t) {
                HubSuite.logger().debug("权限检查失败（{}）：{}", node, t.toString());
            }
        }
        return hubsuite$levelCheck(player, fallbackLevel);
    }

    /** 原版权限等级判定（等级 2 = Game Master）。 */
    private static boolean hubsuite$levelCheck(ServerPlayer player, int level) {
        var permission = switch (level) {
            case 0 -> null;
            case 1 -> net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR;
            case 3 -> net.minecraft.server.permissions.Permissions.COMMANDS_ADMIN;
            case 4 -> net.minecraft.server.permissions.Permissions.COMMANDS_OWNER;
            default -> net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER;
        };
        return permission == null || player.permissions().hasPermission(permission);
    }

    /** 调试用：打印相关节点的判定结果。 */
    public Map<String, Boolean> snapshot(ServerPlayer player) {
        if (player == null) {
            return Map.of();
        }
        return Map.of(
                NODE_ADMIN, hasAdminPermission(player),
                NODE_BUILD_CREATIVE, hasBuildPermission(player),
                NODE_ISLAND_ADMIN, hasIslandAdmin(player));
    }

    /** 供 {@code /hub perms} 展示原始判定（含未知节点，用于验证 LuckPerms 是否接通）。 */
    public boolean rawCheck(ServerPlayer player, String node) {
        if (player == null) {
            return false;
        }
        if (checkSimple != null) {
            try {
                Object result = checkSimple.invoke(null, player, node);
                if (result instanceof Boolean b) {
                    return b;
                }
            } catch (Throwable t) {
                HubSuite.logger().debug("权限检查失败（{}）：{}", node, t.toString());
            }
        }
        return false;
    }
}
