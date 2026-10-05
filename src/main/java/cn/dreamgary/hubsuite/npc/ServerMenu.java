package cn.dreamgary.hubsuite.npc;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.lobby.ServerJoinDialog;
import cn.dreamgary.hubsuite.ui.ChestMenuScreen;
import cn.dreamgary.hubsuite.world.Lobby;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.world.SubServer;
import cn.dreamgary.hubsuite.world.WorldsManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 大厅假人点击后弹出的**箱子式服务器选择界面**。
 *
 * <p>每个子服一个条目，图标按子服 id 挑一个形象的方块，名字下面写上
 * 当前人数与简介。点一下就直接进服。
 *
 * <p>新增子服时这里**不需要改代码** —— 列表是从 {@link WorldsManager#subServers()}
 * 动态生成的。
 */
public final class ServerMenu {

    private ServerMenu() {
    }

    /** 打开界面。 */
    public static void open(ServerPlayer player, WorldsManager worlds) {
        List<ChestMenuScreen.Entry> entries = new ArrayList<>();

        for (SubServer sub : worlds.subServers()) {
            if (!sub.config().enabled) {
                continue;
            }
            entries.add(buildEntry(player, sub));
        }

        // 回大厅的条目（如果玩家不在大厅，顺便给个回去的入口）
        var current = worlds.worldOf(player).orElse(null);
        if (current != null && !Lobby.ID.equals(current.id())) {
            entries.add(ChestMenuScreen.Entry.of(
                    new ItemStack(Items.NETHER_STAR),
                    "\u00A7f\u00A7l返回大厅",
                    List.of("\u00A77回到出生大厅"),
                    p -> ServerJoinDialog.joinById(p, Lobby.ID, worlds)));
        }

        if (entries.isEmpty()) {
            player.sendSystemMessage(cn.dreamgary.hubsuite.ui.Text.of(
                    "\u00A7c当前没有可用的服务器。"));
            return;
        }

        ChestMenuScreen.open(player, "\u00A78\u00A7l点击传送", entries);
    }

    /** 组装一个子服的条目。 */
    private static ChestMenuScreen.Entry buildEntry(ServerPlayer player, SubServer sub) {
        List<String> lore = new ArrayList<>();

        String description = sub.description();
        if (description != null && !description.isBlank()) {
            lore.add("\u00A77" + stripColor(description));
        }

        // 必须用 playerCount()（跨子服全部维度）。
        // sub.level() 是主维度，而空岛服的玩家都在 hub/classic/ocean 里，
        // 用它会永远显示 0 人。
        int online = sub.playerCount();
        lore.add("\u00A78在线：\u00A7f" + online + " \u00A78人");

        String rules = "";
        if (sub.rules() != null) {
            rules = (sub.rules().pvpAllowed() ? "\u00A7cPvP 开" : "\u00A7aPvP 关")
                    + (sub.rules().allowFlight() ? "  \u00A7b飞行 开" : "  \u00A78飞行 关");
        }
        if (!rules.isEmpty()) {
            lore.add(rules);
        }

        lore.add("");
        lore.add("\u00A7e▶ 点击传送");

        boolean here = sub.level().dimension().equals(player.level().dimension());
        String name = here
                ? "\u00A78" + stripColor(sub.displayName()) + " \u00A77(你已在此)"
                : "\u00A7f\u00A7l" + stripColor(sub.displayName());

        return ChestMenuScreen.Entry.of(
                new ItemStack(iconFor(sub.id())),
                name,
                lore,
                p -> {
                    HubSuite.logger().info("{} 在服务器选择界面里点击了 {}",
                            p.getName().getString(), sub.id());
                    ServerJoinDialog.joinById(p, sub.id(), HubSuite.worlds());
                });
    }

    /** 按子服 id 挑一个形象的图标；不认识的就用指南针。 */
    private static net.minecraft.world.item.Item iconFor(String id) {
        return switch (id.toLowerCase(java.util.Locale.ROOT)) {
            case "survival" -> Items.IRON_SWORD;
            case "creative" -> Items.DIAMOND_BLOCK;
            case "skyblock" -> Items.GRASS_BLOCK;
            case "ocean" -> Items.HEART_OF_THE_SEA;
            default -> Items.COMPASS;
        };
    }

    /** 把颜色码去掉（图标名字是纯文本，颜色码会显示成乱码）。 */
    private static String stripColor(String input) {
        return cn.dreamgary.hubsuite.ui.Text.stripColors(input);
    }
}
