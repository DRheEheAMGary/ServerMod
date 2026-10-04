package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家状态在"子服之间"的独立存档。
 *
 * <p><b>为什么需要：</b>原版的玩家背包、经验、末影箱是跟着玩家实体走的。
 * 跨维度传送时原版**不会**重新从磁盘读玩家数据，所以玩家会"背着自己的东西"
 * 进到另一个子服 —— 实测表现为"生存服攒的东西被带到大厅来了"。
 * 这与"每个子服独立背包"的设计冲突。
 *
 * <p><b>做法：</b>用"内存暂存 + 进服时套用"的方式做状态切换：
 * <ol>
 *   <li>离开某个场所前，把玩家当前的背包/经验/末影箱/血量按**场所 id** 暂存起来；</li>
 *   <li>套用目标场所上一次留下的状态（第一次进入就是全新的空状态）；</li>
 *   <li>服务端关闭时把暂存写回各子服的玩家数据文件，保证重启后不丢。</li>
 * </ol>
 *
 * <p>用内存而不是每次读写磁盘：切服是高频操作，磁盘 IO 会带来明显卡顿；
 * 而内存暂存在正常游玩路径上与磁盘版本等价。
 */
public final class PlayerStateStash {

    /** 一个场所里保存的玩家状态。 */
    public static final class State {
        public ItemStack[] main = new ItemStack[36];
        public ItemStack[] armor = new ItemStack[4];
        public ItemStack[] offhand = new ItemStack[1];
        public int selectedSlot;
        public int experienceLevel;
        public float experienceProgress;
        public int totalExperience;
        public float health;
        public int foodLevel;
        public float saturation;
        public boolean initialized;

        static State empty() {
            State s = new State();
            java.util.Arrays.fill(s.main, ItemStack.EMPTY);
            java.util.Arrays.fill(s.armor, ItemStack.EMPTY);
            java.util.Arrays.fill(s.offhand, ItemStack.EMPTY);
            return s;
        }

        boolean isEmpty() {
            for (ItemStack stack : main) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            for (ItemStack stack : armor) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            for (ItemStack stack : offhand) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** 玩家 UUID → （场所 id → 状态）。 */
    private static final Map<UUID, Map<String, State>> STASH = new ConcurrentHashMap<>();

    private PlayerStateStash() {
    }

    // ------------------------------------------------------------------
    // 存取
    // ------------------------------------------------------------------

    /** 把玩家当前状态记到指定的场所名下。 */
    public static void capture(ServerPlayer player, String worldId) {
        try {
            State state = new State();
            Inventory inv = player.getInventory();

            for (int i = 0; i < 36; i++) {
                state.main[i] = inv.getItem(i).copy();
            }
            for (int i = 0; i < 4; i++) {
                state.armor[i] = inv.getItem(36 + i).copy();
            }
            state.offhand[0] = inv.getItem(40).copy();
            state.selectedSlot = inv.getSelectedSlot();

            state.experienceLevel = player.experienceLevel;
            state.experienceProgress = player.experienceProgress;
            state.totalExperience = player.totalExperience;
            state.health = player.getHealth();
            state.foodLevel = player.getFoodData().getFoodLevel();
            state.saturation = player.getFoodData().getSaturationLevel();
            state.initialized = true;

            STASH.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                    .put(worldId, state);
        } catch (Throwable t) {
            HubSuite.logger().error("暂存 {} 在 {} 的状态失败", player.getName().getString(), worldId, t);
        }
    }

    /**
     * 把玩家状态切换成该场所上次留下的状态。
     *
     * <p>该场所没有记录时（首次进入）—— 清空为全新状态。
     */
    public static void apply(ServerPlayer player, String worldId) {
        try {
            Map<String, State> perWorld = STASH.get(player.getUUID());
            State state = perWorld == null ? null : perWorld.get(worldId);
            if (state == null) {
                state = State.empty();
            }
            write(player, state);
        } catch (Throwable t) {
            HubSuite.logger().error("套用 {} 在 {} 的状态失败", player.getName().getString(), worldId, t);
        }
    }

    private static void write(ServerPlayer player, State state) {
        Inventory inv = player.getInventory();

        for (int i = 0; i < 36; i++) {
            inv.setItem(i, state.main[i] == null ? ItemStack.EMPTY : state.main[i].copy());
        }
        for (int i = 0; i < 4; i++) {
            inv.setItem(36 + i, state.armor[i] == null ? ItemStack.EMPTY : state.armor[i].copy());
        }
        inv.setItem(40, state.offhand[0] == null ? ItemStack.EMPTY : state.offhand[0].copy());
        inv.setSelectedSlot(state.selectedSlot);

        // 光标上拿着的东西也清掉/恢复，避免"跨服携带"
        player.containerMenu.setCarried(ItemStack.EMPTY);

        player.experienceLevel = state.experienceLevel;
        player.experienceProgress = state.experienceProgress;
        player.totalExperience = state.totalExperience;

        if (state.initialized) {
            player.setHealth(Math.max(1.0F, Math.min(state.health, player.getMaxHealth())));
            player.getFoodData().setFoodLevel(state.foodLevel);
            player.getFoodData().setSaturation(state.saturation);
        } else {
            // 全新状态：满血满饱食，避免一进新服就饿着
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.experienceLevel = 0;
            player.experienceProgress = 0.0F;
            player.totalExperience = 0;
        }
        player.inventoryMenu.broadcastChanges();
    }

    /** 某个场所是否已有该玩家的状态记录。 */
    public static boolean has(UUID uuid, String worldId) {
        Map<String, State> perWorld = STASH.get(uuid);
        return perWorld != null && perWorld.containsKey(worldId);
    }

    /** 玩家退出服务器时清理（状态已随玩家数据落盘到对应子服）。 */
    public static void forget(UUID uuid) {
        STASH.remove(uuid);
    }

    public static void clear() {
        STASH.clear();
    }
}
