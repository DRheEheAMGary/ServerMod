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

        /** 这个菜单在服务端的容器 id —— 用来判断收到的点击包是不是打给它的。 */
        int containerId;

        /** 打开时间，用于超时自动关闭。 */
        final long openedAt = System.currentTimeMillis();

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
                // 记下服务端实际用的容器 id：后面判断"这个点击包是不是打给我们的"
                // 只能靠它，不能靠 player.containerMenu（那时可能已经是别的容器了）
                open.containerId = syncId;
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
        /*
         * ★ 必须比对**我们自己记下的 containerId**，而不是 player.containerMenu。
         *
         * 踩过的坑（用户反馈"全服都打不开箱子"）：
         * 原来写的是
         *     player.containerMenu.containerId != containerId → return false
         * 这个判断在"玩家打开真实箱子"时**恰好成立**（当前菜单就是那个箱子，
         * id 自然等于包里的 id），于是这张残留的 OPEN 记录被误判成"是我们的菜单"：
         *   · 玩家按住右键 → 箱子刚打开，连点就变成箱子里的点击
         *   · 命中残留记录 → 执行 closeContainer() → **界面秒关**
         * 表现就是"箱子怎么点都打不开"。
         *
         * 而且因为大厅的引导假人菜单人人都会开一次，这个问题在**所有子服**
         * 都会出现（用户实测："全服都打不开箱子"）。
         */
        if (open.containerId != containerId) {
            return false;
        }
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

        /*
         * ★ 动作**延后一 tick** 执行，不能和 closeContainer 挤在同一个 tick。
         *
         * 踩过的坑（用户反馈"开过菜单之后右键就完全没反应了"）：
         * 原来这里紧接着就执行 onClick()（多半是跨维度传送），于是客户端在
         * "容器正在关闭"的中间状态里被直接切了维度。在接管了界面栈的客户端模组
         * （例如 Modern UI）下，这会留下一个**看不见但仍然吃输入**的屏幕 ——
         * 表现就是之后右键完全没动静、连数据包都不发（原版在有界面打开时
         * 右键不会发包）。
         *
         * 延后一 tick 让关闭包先被客户端处理完，再触发传送/开新界面。
         */
        var server = player.level().getServer();
        if (server != null) {
            server.execute(() -> runEntry(entry, player));
        } else {
            runEntry(entry, player);
        }
        return true;
    }

    /** 执行菜单条目的动作（与关闭容器隔开一 tick）。 */
    private static void runEntry(Entry entry, ServerPlayer player) {
        try {
            entry.onClick().accept(player);
        } catch (Throwable t) {
            HubSuite.logger().error("菜单条目 '{}' 的回调执行失败", entry.name(), t);
            player.sendSystemMessage(Text.of("\u00A7c这个选项执行失败，请查看控制台。"));
        }
    }

    /** 玩家关闭界面时清理。 */
    /**
     * 每个 tick 清理"已经不在前台"的菜单记录。
     *
     * <p>玩家按 ESC 关掉界面时，`OPEN` 里的记录不会被清（`forget` 只在
     * 成功点击和退出游戏时调用）。残留记录本身不会立刻出事，但一旦玩家
     * 随后打开**真实容器**，那条记录就可能被误判成"当前是我们的菜单" ——
     * 见 {@link #handleClick} 里的说明。
     *
     * <p>这里不依赖 ESC 事件（数据包不一定可靠），而是直接比对
     * "玩家现在真正打开的容器 id"：对不上就把记录清掉。
     */
    public static void tick(net.minecraft.server.MinecraftServer server) {
        if (OPEN.isEmpty()) {
            return;
        }
        var players = server.getPlayerList();
        OPEN.entrySet().removeIf(entry -> {
            Open open = entry.getValue();
            var player = players.getPlayer(entry.getKey());
            if (player == null || player.containerMenu == null
                    || player.containerMenu.containerId != open.containerId) {
                return true;   // 已经不在前台了
            }
            /*
             * 保险：菜单开太久（5 分钟）没任何交互就主动关掉。
             *
             * 目的是别让"服务端认为菜单开着"一直持续 —— 一些客户端模组会在
             * 界面栈被外部改动后留下一个看不见但仍吃输入的屏幕，
             * 那种情况下右键会完全失效。主动关闭能让状态回到干净的原版流程。
             */
            if (System.currentTimeMillis() - open.openedAt > 5 * 60 * 1000L) {
                HubSuite.logger().info("菜单开启超时，主动关闭：{}",
                        player.getName().getString());
                player.closeContainer();
                return true;
            }
            return false;
        });
    }

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
