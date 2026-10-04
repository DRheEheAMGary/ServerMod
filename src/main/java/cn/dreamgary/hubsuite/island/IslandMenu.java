package cn.dreamgary.hubsuite.island;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.ui.ChestMenuScreen;
import cn.dreamgary.hubsuite.world.Lobby;
import cn.dreamgary.hubsuite.world.WorldsManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 空岛服大厅里那个假人点开的**岛型选择界面**（箱子式）。
 *
 * <p>两个条目：经典空岛 与 海岛。点一下就建岛（没有的话）并传送过去。
 * 另外给一个"返回服务器大厅"的出口 —— 否则玩家进了空岛服就只能在
 * 两个岛和大厅之间转，回不去主大厅。
 */
public final class IslandMenu {

    private IslandMenu() {
    }

    /** 打开界面。 */
    public static void open(ServerPlayer player, IslandService service, WorldsManager worlds) {
        List<ChestMenuScreen.Entry> entries = new ArrayList<>();

        for (IslandService.Type type : service.types()) {
            entries.add(buildEntry(player, service, type));
        }

        // 出口：回主大厅
        entries.add(ChestMenuScreen.Entry.of(
                new ItemStack(Items.COMPASS),
                "\u00A7f\u00A7l返回服务器大厅",
                List.of("\u00A77回到出生大厅，选择别的服务器"),
                p -> {
                    if (worlds != null) {
                        worlds.lobby().ifPresent(lobby -> {
                            var current = worlds.worldOf(p).orElse(null);
                            if (current != null && Lobby.ID.equals(current.id())) {
                                return;
                            }
                            cn.dreamgary.hubsuite.world.PlayerRouter.sendTo(p, lobby);
                        });
                    }
                }));

        ChestMenuScreen.open(player, "\u00A78\u00A7l选择岛屿", entries);
    }

    /** 组装一个岛型的条目。 */
    private static ChestMenuScreen.Entry buildEntry(ServerPlayer player,
                                                    IslandService service,
                                                    IslandService.Type type) {
        var manager = type.manager();
        var typeConfig = manager.config().type(type.id());
        var existing = manager.islandOf(player.getUUID());

        List<String> lore = new ArrayList<>();
        String description = typeConfig == null ? "" : typeConfig.description;
        if (description != null && !description.isBlank()) {
            lore.add("\u00A77" + cn.dreamgary.hubsuite.ui.Text.stripColors(description));
        }

        if (existing.isPresent()) {
            var island = existing.get();
            lore.add("\u00A78你的岛：方格 (" + island.plotX + ", " + island.plotZ + ")");
            lore.add("\u00A78坐标：" + manager.plotCenter(island.plotX, island.plotZ).toShortString());
            lore.add("");
            lore.add("\u00A7e▶ 点击回岛");
        } else {
            lore.add("\u00A78你还没有这种岛");
            lore.add("");
            lore.add("\u00A7e▶ 点击创建并进入");
        }

        return ChestMenuScreen.Entry.of(
                new ItemStack(iconFor(type.id())),
                "\u00A7f\u00A7l" + (typeConfig == null ? type.id() : cn.dreamgary.hubsuite.ui.Text.stripColors(typeConfig.displayName)),
                lore,
                p -> {
                    HubSuite.logger().info("{} 在岛型界面里选择了 {}（已有岛={}）",
                            p.getName().getString(), type.id(), existing.isPresent());
                    if (!service.visit(p, type.id())) {
                        p.sendSystemMessage(cn.dreamgary.hubsuite.ui.Text.of(
                                "\u00A7c进入失败，请联系管理员查看控制台。"));
                    }
                });
    }

    /** 按岛型挑图标。 */
    private static net.minecraft.world.item.Item iconFor(String typeId) {
        return switch (typeId.toLowerCase(java.util.Locale.ROOT)) {
            case "classic" -> Items.GRASS_BLOCK;
            case "ocean" -> Items.HEART_OF_THE_SEA;
            default -> Items.OAK_SAPLING;
        };
    }
}
