package cn.dreamgary.hubsuite.lobby;

import cn.dreamgary.hubsuite.auth.AuthManager;
import cn.dreamgary.hubsuite.npc.HubNpc;
import cn.dreamgary.hubsuite.npc.NpcManager;
import cn.dreamgary.hubsuite.ui.DialogKit;
import cn.dreamgary.hubsuite.world.PlayerRouter;
import cn.dreamgary.hubsuite.world.WorldsManager;
import cn.dreamgary.hubsuite.ui.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * "进入某个子服"的确认对话框，以及真正的进服动作。
 *
 * <p>对话框里会展示该子服的实时状态（在线人数、世界类型、游戏模式、难度、
 * PvP 是否开启），让玩家点之前就知道自己要进什么地方。
 */
public final class ServerJoinDialog {

    public static final String ACTION_JOIN_PREFIX = "lobby/join/";
    public static final String ACTION_CANCEL = "lobby/cancel";

    private ServerJoinDialog() {
    }

    /** 右键 NPC 时弹出确认框。 */
    public static void open(ServerPlayer player, HubNpc npc, WorldsManager worlds, NpcManager npcManager) {
        var sub = npc.target();
        var cfg = sub.config();
        var rules = sub.rules();

        String info = String.join("\n",
                "\u00A77在线人数：\u00A7f" + sub.playerCount(),
                "\u00A77世界类型：\u00A7f" + cfg.worldKind,
                "\u00A77游戏模式：\u00A7f" + cfg.gameMode,
                "\u00A77难度：\u00A7f" + cfg.difficulty,
                "\u00A77PvP：\u00A7f" + (rules.pvpAllowed() ? "\u00A7c开启" : "\u00A7a关闭"),
                "\u00A77允许飞行：\u00A7f" + (rules.allowFlight() ? "是" : "否"),
                "",
                "\u00A78左键 NPC 可直接进入");

        var yes = DialogKit.customButton(Text.of("\u00A7a进入 " + sub.displayName()),
                DialogKit.action(ACTION_JOIN_PREFIX + sub.id()), Optional.empty());
        var no = DialogKit.customButton(Component.literal("\u00A77再看看"), DialogKit.action(ACTION_CANCEL), Optional.empty());

        DialogKit.open(player, DialogKit.confirm(
                Text.of("\u00A7b" + sub.displayName()),
                Component.literal(info),
                yes,
                no));
    }

    /** 直接进服（左键 NPC 或确认框点"进入"）。 */
    public static void join(ServerPlayer player, HubNpc npc) {
        var sub = npc.target();
        if (PlayerRouter.sendTo(player, sub)) {
            player.sendSystemMessage(Text.of(
                    "\u00A7a已进入 " + sub.displayName() + "\u00A7a。\u00A77输入 \u00A7f/hub \u00A77可返回大厅。"));
            // 进服后给一份该子服的简要说明
            player.sendSystemMessage(Text.of("\u00A78" + sub.description()));
        }
    }

    /** 供 DialogRouter 注册用：从动作 id 解析出子服并进服。 */
    public static void joinById(ServerPlayer player, String serverId, WorldsManager worlds) {
        worlds.subServer(serverId).ifPresentOrElse(
                sub -> {
                    if (PlayerRouter.sendTo(player, sub)) {
                        player.sendSystemMessage(Text.of(
                                "\u00A7a已进入 " + sub.displayName() + "\u00A7a。\u00A77输入 \u00A7f/hub \u00A77可返回大厅。"));
                    }
                },
                () -> player.sendSystemMessage(Text.of("\u00A7c子服不存在：" + serverId)));
    }

    /** 给 /hub list 之外的展示用。 */
    public static List<String> describeAll(WorldsManager worlds) {
        return worlds.subServers().stream()
                .map(s -> s.displayName() + " \u00A77(" + s.id() + ") \u00A7f" + s.playerCount() + " 人")
                .toList();
    }

    /** 未使用的保留签名，便于以后加权限校验。 */
    static boolean requiresPermission(AuthManager manager, ServerPlayer player) {
        return manager == null || manager.isAuthenticated(player);
    }
}
