package cn.dreamgary.hubsuite.ui;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 箱子式图形菜单（用箱子界面做选项列表）。
 *
 * <p><b>为什么不用原版容器点击逻辑：</b>箱子菜单默认允许把物品拿走。
 * 我们在 {@code ServerGamePacketListenerImplMixin} 里**完全接管**点击处理：
 * 取消原版逻辑，只读槽位号并派发给注册的回调。
 * 这样物品既不会被拿走，也不会因为 shift 点击、拖拽、数字键而产生任何副作用。
 *
 * <p>每个玩家同一时刻只保留一个打开的菜单；关掉界面或重新打开会替换旧的。
 */
public final class ChestMenuScreen {

    /** 一个条目：图标 + 名字 + 描述 + 点击回调。 */
    public record Entry(ItemStack icon, String name, List<String> lore, Consumer<ServerPlayer> onClick) {

        public static Entry of(ItemStack icon, String name, Consumer<ServerPlayer> onClick) {
            return new Entry(icon, name, List.of(), onClick);
        }

        public static Entry of(ItemStack icon, String name, List<String> lore,
                               Consumer<ServerPlayer> onClick) {
            return new Entry(icon, name, lore, onClick);
        }
    }

    /** 一个玩家当前打开的菜单：容器 + 条目表。 */
    private static final class Open {
        final SimpleContainer container;
        final List<Entry> entries;
        final String title;

        Open(SimpleContainer container, List<Entry> entries, String title) {
            this.container = container;
            this.entries = entries;
            this.title = title;
        }
    }

    private static final Map<UUID, Open> OPEN = new HashMap<>();

    private ChestMenuScreen() {
    }

    /**
     * 打开一个箱子菜单。
     *
     * @param title   箱子界面顶部的标题
     * @param entries 条目（按槽位顺序，最多 54 个）
     */
    public static void open(ServerPlayer player, String title, List<Entry> entries) {
        int slots = Math.max(9, Math.min(54, ((entries.size() + 8) / 9) * 9));
        SimpleContainer container = new SimpleContainer(slots);

        for (int i = 0; i < entries.size() && i < slots; i++) {
            container.setItem(i, decorate(entries.get(i)));
        }

        Open open = new Open(container, new ArrayList<>(entries), title);
        OPEN.put(player.getUUID(), open);

        MenuType<?> type = switch (slots / 9) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };

        player.openMenu(new net.minecraft.world.MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Text.of(title);
            }

            @Override
            public ChestMenu createMenu(int syncId, net.minecraft.world.entity.player.Inventory inventory,
                                        net.minecraft.world.entity.player.Player p) {
                return new ChestMenu(type, syncId, inventory, container, slots / 9);
            }
        });
    }

    /** 给图标套上名字与描述。 */
    private static ItemStack decorate(Entry entry) {
        ItemStack stack = entry.icon().copy();
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Text.of(entry.name()));
        if (!entry.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : entry.lore()) {
                lore.add(Text.of(line));
            }
            stack.set(net.minecraft.core.component.DataComponents.LORE,
                    new net.minecraft.world.item.component.ItemLore(lore));
        }
        return stack;
    }

    /**
     * 处理一次点击。返回 true 表示"这个点击属于我方菜单，已接管"。
     *
     * <p>由 {@code ServerGamePacketListenerImplMixin} 在包处理的最前面调用。
     */
    public static boolean handleClick(ServerPlayer player, int containerId, int slot) {
        Open open = OPEN.get(player.getUUID());
        if (open == null) {
            return false;
        }
        // 容器 id 对不上说明玩家看的不是这个菜单（例如被别的界面顶掉了）
        if (player.containerMenu == null || player.containerMenu.containerId != containerId) {
            return false;
        }

        // 只要点的是箱子区域内的槽位就算我方菜单（负数是界面外，忽略）
        if (slot < 0 || slot >= open.entries.size()) {
            return true;   // 点空白格：吞掉，不做任何事
        }

        Entry entry = open.entries.get(slot);
        // 先关界面再执行动作：动作可能会传送玩家/打开新界面
        player.closeContainer();
        OPEN.remove(player.getUUID());

        try {
            entry.onClick().accept(player);
        } catch (Throwable t) {
            HubSuite.logger().error("菜单条目 '{}' 的回调执行失败", entry.name(), t);
            player.sendSystemMessage(Text.of("\u00A7c这个选项执行失败，请查看控制台。"));
        }
        return true;
    }

    /** 玩家关闭界面时清理。 */
    public static void forget(UUID uuid) {
        OPEN.remove(uuid);
    }

    /** 这个玩家当前是否开着菜单。 */
    public static boolean hasOpen(UUID uuid) {
        return OPEN.containsKey(uuid);
    }

    /** 拖拽/快捷移动等操作也要吞掉，避免物品被搬走。 */
    public static boolean shouldBlockInteraction(ServerPlayer player, int containerId) {
        Open open = OPEN.get(player.getUUID());
        if (open == null) {
            return false;
        }
        return player.containerMenu != null && player.containerMenu.containerId == containerId;
    }
}
