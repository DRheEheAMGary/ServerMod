package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

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
 * <p><b>两层结构（缺一不可）：</b>
 * <ol>
 *   <li><b>内存暂存</b>：切服时把玩家当前状态按维度记下来，立刻套用目标维度那份。
 *       切服是高频操作，纯磁盘 IO 会明显卡顿。</li>
 *   <li><b>该维度的玩家数据文件</b>：内存暂存**只在一次会话内有效**，
 *       所以每次离开一个维度时都要把它<b>强制落盘</b>；
 *       而进入一个内存里没有记录的维度时（换会话/重启后第一次进），
 *       就从那个维度的存档里读回来。</li>
 * </ol>
 *
 * <p><b>踩过的坑（严重数据丢失）：</b>第一版只有第 1 层 ——
 * 类注释写着"服务端关闭时把暂存写回各子服的玩家数据文件"，
 * 但关服时实际只调了 {@code clear()}，也就是说那些文件**只写不读**：
 * <pre>
 *   在生存服攒了东西 → 关服 → 重新登录 → 进生存服 → 背包空了
 * </pre>
 * 磁盘上 {@code hubsuite_survival/players/data/&lt;uuid&gt;.dat} 一直好好的，
 * 只是没有任何代码去读它。而原来的自检只测"同一次会话里切服往返"（走内存），
 * 完全测不到 —— 现在 {@code checkStateSurvivesRestart} 会把这条钉住。
 *
 * <p><b>为什么按维度而不是按子服：</b>空岛服有大厅/经典/海岛三个维度，
 * 每个维度都是独立存档、独立背包，按子服 id 存会让三者互相覆盖。
 */
public final class PlayerStateStash {

    /** 一个维度里保存的玩家状态。 */
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
        /**
         * 玩家登出时骑着谁（实体 UUID 字符串）。
         *
         * <p>用途："坐在船上退出再进来，船不见了"（用户实测）。原版把坐骑写进
         * 玩家数据的 {@code RootVehicle}，重进时靠 {@code ServerPlayer#loadAndSpawnParentVehicle}
         * 在**玩家落地的那一界**里找回来；而本模组登录时先把人放进大厅
         * （见 {@code PrepareSpawnTaskMixin}），玩家数据又按维度分开读
         * （{@link #apply} 只搬背包/经验/血量），那个 RootVehicle 就没人处理了。
         * 所以在这里记一份，等玩家真的进入那一界再骑回去（见 {@link #restoreMount}）。
         */
        public String rootVehicleUuid;
        /**
         * 登出时那条坐骑的原始 NBT（原版写的 {@code RootVehicle} 整块）。
         *
         * <p>为什么要留原始 NBT：原版在玩家断线时会**把只有一个玩家乘客的载具清掉**
         * （见 {@code PlayerList.remove}：{@code isPassenger()} +
         * {@code hasExactlyOnePlayerPassenger()} → {@code stopRiding()} → 丢掉载具），
         * 把它的完整 NBT 存进玩家数据，等下次登录再重建。
         * 本模组登录先落大厅、数据按维度分开读，那一步没人为我们做 ——
         * 所以要么按 UUID 找回还在那一界的载具，要么照这份 NBT 重建（见 #restoreMount）。
         */
        public CompoundTag rootVehicleTag;

        static State empty() {
            State s = new State();
            blank(s);
            return s;
        }

        static void blank(State s) {
            java.util.Arrays.fill(s.main, ItemStack.EMPTY);
            java.util.Arrays.fill(s.armor, ItemStack.EMPTY);
            java.util.Arrays.fill(s.offhand, ItemStack.EMPTY);
        }
    }

    /** 玩家 UUID → （维度 id → 状态）。 */
    private static final Map<UUID, Map<String, State>> STASH = new ConcurrentHashMap<>();

    private PlayerStateStash() {
    }

    // ------------------------------------------------------------------
    // 存取
    // ------------------------------------------------------------------

    /** 把玩家当前状态记到指定维度名下，并**立刻落盘**到该维度的玩家数据文件。 */
    public static void capture(ServerPlayer player, ResourceKey<Level> dimension) {
        String worldId = id(dimension);
        try {
            State state = new State();
            read(player, state);
            STASH.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                    .put(worldId, state);
        } catch (Throwable t) {
            HubSuite.logger().error("暂存 {} 在 {} 的状态失败",
                    player.getName().getString(), worldId, t);
        }
        // 落盘必须在**玩家还站在旧维度、身上还是旧状态**的时候做：
        // 否则内存暂存一清（关服/掉线），这份状态就再也没人写进那个维度的存档了。
        persist(player, dimension);
    }

    /**
     * 把玩家状态切换成该维度上次留下的状态。
     *
     * <p>内存里没有记录时（换会话、重启后第一次进）从该维度的玩家数据文件读；
     * 文件也没有（全新玩家）才清空为全新状态。
     */
    public static void apply(ServerPlayer player, ResourceKey<Level> dimension) {
        String worldId = id(dimension);
        try {
            Map<String, State> perWorld = STASH.get(player.getUUID());
            State state = perWorld == null ? null : perWorld.get(worldId);
            if (state == null) {
                // 内存没有 → 问磁盘（这是"重新登录后东西还在"的唯一来源）
                state = loadFromSave(player, dimension);
                if (state != null) {
                    STASH.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>())
                            .put(worldId, state);
                }
            }
            if (state == null) {
                state = State.empty();
            }
            write(player, state);
        } catch (Throwable t) {
            HubSuite.logger().error("套用 {} 在 {} 的状态失败",
                    player.getName().getString(), worldId, t);
        }
    }

    private static String id(ResourceKey<Level> dimension) {
        return dimension.identifier().toString();
    }

    // ------------------------------------------------------------------
    // 内存 ↔ 实体
    // ------------------------------------------------------------------

    /** 把实体当前状态读进 State。 */
    private static void read(ServerPlayer player, State state) {
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
        var vehicle = player.getVehicle();
        state.rootVehicleUuid = vehicle == null ? null : vehicle.getUUID().toString();
        state.initialized = true;
    }

    /** 把 State 写进实体。 */
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

    // ------------------------------------------------------------------
    // 磁盘
    // ------------------------------------------------------------------

    /**
     * 把玩家当前状态写进该维度的玩家数据文件。
     *
     * <p>用 {@code PlayerDataStorage.save(Player)}：它就是原版自动保存时调的那个，
     * 路径与 {@code PlayerListStorageMixin} 路由过去的完全一致（都是按维度取存储）。
     */
    private static void persist(ServerPlayer player, ResourceKey<Level> dimension) {
        try {
            var storage = PlayerDataRouter.storageForDimension(dimension);
            if (storage == null) {
                // 维度未托管（例如原版主世界）：没有对应存档，交给原版
                return;
            }
            storage.save(player);
        } catch (Throwable t) {
            HubSuite.logger().warn("把 {} 的状态落盘到 {} 失败：{}",
                    player.getName().getString(), id(dimension), t.toString());
        }
    }

    /**
     * 从该维度的玩家数据文件里读回状态。
     *
     * <p>只取"状态类"字段（背包/盔甲/副手/选中格/经验/血量/饱食），
     * 位置、视角、维度这些**不碰** —— 落点由出生点解析器决定，
     * 用这里的旧坐标会把玩家拽回上次的位置。
     */
    private static State loadFromSave(ServerPlayer player, ResourceKey<Level> dimension) {
        var storage = PlayerDataRouter.storageForDimension(dimension);
        var server = player.level().getServer();
        if (storage == null || server == null) {
            return null;
        }
        var maybeTag = storage.load(new NameAndId(player.getUUID(), player.getName().getString()));
        if (maybeTag.isEmpty()) {
            return null;
        }
        CompoundTag tag = maybeTag.get();
        State state = State.empty();

        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());

        // 原版把整个背包（含盔甲 36-39、副手 40）存成一个列表
        var list = tag.getListOrEmpty("Inventory");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompoundOrEmpty(i);
            int slot = itemTag.getIntOr("Slot", -1);
            if (slot < 0 || slot > 40) {
                continue;
            }
            // ItemStack.CODEC 会忽略多出来的 "Slot" 字段（MapCodec 不校验未知键）
            ItemStack stack = ItemStack.CODEC.parse(ops, itemTag)
                    .result().orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) {
                continue;
            }
            if (slot < 36) {
                state.main[slot] = stack;
            } else if (slot < 40) {
                state.armor[slot - 36] = stack;
            } else {
                state.offhand[0] = stack;
            }
        }

        state.selectedSlot = tag.getIntOr("SelectedItemSlot", 0);
        state.experienceLevel = tag.getIntOr("XpLevel", 0);
        state.experienceProgress = tag.getFloatOr("XpP", 0.0F);
        state.totalExperience = tag.getIntOr("XpTotal", 0);
        state.health = tag.getFloatOr("Health", player.getMaxHealth());
        state.foodLevel = tag.getIntOr("foodLevel", 20);
        state.saturation = tag.getFloatOr("foodSaturationLevel", 5.0F);
        // RootVehicle 整块留着（原版 saveParentVehicle 写的）：Attach 是那个实体的 UUID，
        // Entity 是它的完整 NBT —— 原版断线时会清掉载具，只能靠这份 NBT 重建。
        CompoundTag rootVehicle = tag.getCompoundOrEmpty("RootVehicle");
        state.rootVehicleTag = rootVehicle.isEmpty() ? null : rootVehicle;
        state.rootVehicleUuid = rootVehicle
                .read("Attach", net.minecraft.core.UUIDUtil.CODEC)
                .map(java.util.UUID::toString)
                .orElse(null);
        state.initialized = true;
        return state;
    }

    /**
     * 重登 / 切服后骑回登出时的那条坐骑（船、矿车、马…）。
     *
     * <p><b>为什么需要：</b>见 {@link State#rootVehicleUuid}。原版是在玩家落地的
     * 那一界里找 RootVehicle；本模组把人放进大厅、数据按维度分开读，于是没人处理它，
     * 用户看到的就是"坐在船上退出重进，船不见了"。
     *
     * <p>两条路，按原版的行为来：
     * <ol>
     *   <li><b>载具还在这一界</b>（会话内切服就是这种：载具留在原维度没被清）→
     *       按 UUID 找回来，直接骑上；</li>
     *   <li><b>载具已经被原版清掉</b>（断线时 {@code PlayerList.remove} 会丢掉
     *       只有一个玩家乘客的载具）→ 照玩家数据里的 {@code RootVehicle} NBT 重建，
     *       就是原版 {@code loadAndSpawnParentVehicle} 做的事
     *       （它带 {@code addWithUUID} 去重，不会和还活着的那条重复）。</li>
     * </ol>
     *
     * <p>必须在**传送之后**调：重建/查找都发生在玩家已经落脚的那个维度里。
     */
    public static void restoreMount(ServerPlayer player, ResourceKey<Level> dimension) {
        try {
            if (player.getVehicle() != null) {
                return;   // 已经骑着了（原版或别的路径处理过），不要抢
            }
            Map<String, State> perWorld = STASH.get(player.getUUID());
            State state = perWorld == null ? null : perWorld.get(id(dimension));
            if (state == null) {
                return;
            }
            var level = player.level();
            if (level == null || !level.dimension().equals(dimension)) {
                return;   // 还没真的进到那一界
            }

            // ① 载具还活着 → 按 UUID 骑回去
            if (state.rootVehicleUuid != null && !state.rootVehicleUuid.isBlank()) {
                var vehicle = level.getEntity(java.util.UUID.fromString(state.rootVehicleUuid));
                if (vehicle != null && vehicle.isAlive()) {
                    player.startRiding(vehicle, true, false);
                    if (player.getVehicle() != null) {
                        HubSuite.logger().debug("已把 {} 骑回坐骑 {}", player.getName().getString(),
                                state.rootVehicleUuid);
                        return;
                    }
                }
            }

            // ② 断线重登：原版在 disconnect 时已经把载具清掉了，只剩这份 NBT。
            //    照它把载具加载回来，但**已经存在于这一界的实体直接复用** ——
            //    原版那套 {@code addWithUUID} 遇到已存在的 UUID 会返回 null、什么都不做，
            //    于是"实体还在但就是骑不上"（自检实测抓到过：getVehicle=null）。
            if (state.rootVehicleTag != null && !state.rootVehicleTag.isEmpty()) {
                var server = level.getServer();
                if (server == null) {
                    return;
                }
                var entityInput = net.minecraft.world.level.storage.TagValueInput.create(
                        net.minecraft.util.ProblemReporter.DISCARDING, server.registryAccess(),
                        state.rootVehicleTag.getCompoundOrEmpty("Entity"));
                var mounted = net.minecraft.world.entity.EntityType.loadEntityRecursive(
                        entityInput, level, net.minecraft.world.entity.EntitySpawnReason.LOAD, e -> {
                            var existing = level.getEntity(e.getUUID());
                            if (existing != null) {
                                return existing;   // 这一界已经有了 → 复用它，别再建一条
                            }
                            return level.addWithUUID(e) ? e : null;
                        });
                if (mounted != null) {
                    player.startRiding(mounted, true, false);
                    if (player.getVehicle() != null) {
                        HubSuite.logger().debug("已为 {} 找回保存的坐骑 {}",
                                player.getName().getString(), mounted.getUUID());
                    }
                }
            }
        } catch (Throwable t) {
            // 坐骑找不回来不是致命问题，绝不能因此影响传送
            HubSuite.logger().debug("恢复坐骑失败：{}", t.toString());
        }
    }

    /** 某个维度是否已有该玩家的状态记录（内存里的）。 */
    public static boolean has(UUID uuid, ResourceKey<Level> dimension) {
        Map<String, State> perWorld = STASH.get(uuid);
        return perWorld != null && perWorld.containsKey(id(dimension));
    }

    /**
     * 玩家退出服务器时清理内存暂存。
     *
     * <p>清得掉是因为状态已经落盘了：每次离开维度时 {@link #capture} 会强制写文件，
     * 退出时原版还会把最终状态写进当前维度的文件。
     */
    public static void forget(UUID uuid) {
        STASH.remove(uuid);
    }

    public static void clear() {
        STASH.clear();
    }
}
