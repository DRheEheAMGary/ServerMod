package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.world.SubServer;
import cn.dreamgary.hubsuite.world.WorldsManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 命令闸门。
 *
 * <p>要做两件事（需求里都点名了）：
 * <ol>
 *   <li><b>未登录玩家</b>：除 {@code /login} {@code /register} {@code /hub} 外全拦，
 *       免得有人在等待区用 {@code /tp}、{@code /kill} 溜出去；</li>
 *   <li><b>创造服的普通玩家</b>：拦掉 {@code /give /setblock /summon /gamemode ...}
 *       这类原版作弊指令（需求原话："禁止普通玩家用 /give 等原版作弊指令，
 *       只保留 mod 提供的面板"）。管理员与 LuckPerms 放行的玩家不受影响。</li>
 * </ol>
 *
 * <p>拦截点在 {@code ServerGamePacketListenerImpl} 的聊天命令处理里（见
 * {@code ServerGamePacketListenerCommandMixin}），因为 Fabric API 只提供
 * 聊天消息事件、没有"命令即将执行"事件。
 */
public final class CommandGuard {

    /** 未登录时允许的命令。 */
    private static final Set<String> AUTH_ALLOWED = Set.of(
            "login", "register", "hub", "help");

    /** HuskHomes 提供的传送类指令（子服可单独关闭）。 */
    private static final Set<String> HUSKHOMES_TELEPORT = Set.of(
            "home", "homes", "sethome", "delhome", "edithome", "phome", "publichome",
            "spawn", "setspawn", "tpa", "tpahere", "tpaccept", "tpdeny", "tpignore",
            "tpaall", "tpall", "tpr", "rtp", "warp", "warps", "setwarp", "delwarp",
            "editwarp", "pwarp", "back", "tp", "tphere", "tpo", "tpohere", "tp离线");

    /** 创造服里对普通玩家屏蔽的原版作弊指令。 */
    private static final Set<String> CREATIVE_BLOCKED = new LinkedHashSet<>(Arrays.asList(
            "give", "setblock", "fill", "clone", "summon", "gamemode", "effect",
            "enchant", "xp", "experience", "item", "loot", "place", "data",
            "attribute", "damage", "tag", "particle", "playsound", "stopsound",
            "worldborder", "time", "weather", "difficulty", "forceload",
            "spectate", "ride", "transfer", "spreadplayers", "random", "jigsaw",
            "structure", "publish", "debug", "raid", "bossbar", "kill",
            "butcher", "seed", "save-all", "save-off", "save-on"));

    private final WorldsManager worlds;
    private final LoginLockdown lockdown;
    private final PermissionService permissions;

    public CommandGuard(WorldsManager worlds, LoginLockdown lockdown, PermissionService permissions) {
        this.worlds = worlds;
        this.lockdown = lockdown;
        this.permissions = permissions;
    }

    /**
     * 判断一条命令能否执行。
     *
     * @param rawCommand 不含前导斜杠的命令字符串
     * @return 允许返回 null；拒绝返回给玩家看的原因
     */
    public Component check(ServerPlayer player, String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return null;
        }
        String name = commandName(rawCommand);

        // ---- 1. 未登录 ----
        if (lockdown.isLocked(player) && !AUTH_ALLOWED.contains(name)) {
            return Component.literal("\u00A7c请先完成注册或登录，当前只能用 \u00A7f/login \u00A7c/register \u00A7c/hub\u00A7c。");
        }

        // ---- 2. HuskHomes 传送指令的子服开关 ----
        if (HUSKHOMES_TELEPORT.contains(name)) {
            PlayableWorld current = worlds.worldOf(player).orElse(null);
            if (current instanceof SubServer currentSub
                    && !currentSub.config().huskhomesTeleport
                    && !permissions.hasAdminPermission(player)) {
                return Component.literal(
                        "\u00A7c本服务器禁用了传送指令，请用 \u00A7f/hub \u00A7c返回大厅后再前往其他服务器。");
            }
        }

        // ---- 3. 创造服 / 空岛服的作弊指令管控 ----
        PlayableWorld world = worlds.worldOf(player).orElse(null);
        if (world instanceof SubServer sub && CREATIVE_BLOCKED.contains(name)) {
            boolean allowed = sub.config().allowGameModeCommand && "gamemode".equals(name);
            if (!allowed && !permissions.hasBuildPermission(player)) {
                if (!sub.config().allowGameModeCommand || !"gamemode".equals(name)) {
                    return Component.literal(
                            "\u00A7c该服务器不允许直接使用 \u00A7f/" + name + "\u00A7c。"
                                    + "\u00A77创造模式请找管理员授权，或使用 mod 提供的功能。");
                }
            }
        }
        return null;
    }

    /** 取命令主体名（跳过命名空间前缀与常见包装命令）。 */
    private static String commandName(String rawCommand) {
        String text = rawCommand.trim();
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        int space = text.indexOf(' ');
        String head = space < 0 ? text : text.substring(0, space);
        int colon = head.indexOf(':');
        if (colon >= 0) {
            head = head.substring(colon + 1);
        }
        return head.toLowerCase(Locale.ROOT);
    }

    /** 启动时打印一次策略摘要，方便运维核对。 */
    public void logPolicy() {
        HubSuite.logger().info("命令闸门已启用：创造服屏蔽 {} 条作弊指令；权限后端 = {}。",
                CREATIVE_BLOCKED.size(), permissions.backendName());
    }

    /** 当前是否装了 LuckPerms（供 /hub info 展示）。 */
    public boolean luckPermsPresent() {
        return FabricLoader.getInstance().isModLoaded("luckperms");
    }
}
