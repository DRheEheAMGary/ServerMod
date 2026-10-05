package cn.dreamgary.hubsuite.island;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.world.PlayerRouter;
import cn.dreamgary.hubsuite.world.SubServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 空岛服务：**一个子服、多个维度**。
 *
 * <pre>
 *   skyblock_hub      公共大厅（出生平台 + 选岛型的假人）
 *   skyblock_classic  经典空岛维度（网格方格，每人格子一座）
 *   skyblock_ocean    海岛维度（自然海洋世界）
 * </pre>
 *
 * <p>每个岛屿维度有自己的 {@link IslandManager}，归属记录也分开存
 * （{@code islands-classic.json} / {@code islands-ocean.json}）——
 * 这样一个人可以同时拥有经典空岛和海岛，互不影响。
 *
 * <p>进入时的分流由 {@link SubServer#setEntryResolver} 完成：
 * <ul>
 *   <li>玩家在岛屿维度里 → 回那座岛；</li>
 *   <li>否则 → 回大厅。</li>
 * </ul>
 */
public final class IslandService {

    /** {@link #pendingDestination} 里表示"这次去大厅"的标记。 */
    private static final String HUB_MARKER = "\u0000hub";

    /** 一种岛型 + 它对应的维度 + 管理器。 */
    public record Type(String id, SubServer.Entry entry, IslandManager manager) {
    }

    private final SubServer server;
    private final SubServer.Entry hubEntry;
    private final Map<String, Type> types = new LinkedHashMap<>();
    private final IslandConfig config;

    /**
     * "这个玩家下一步想去哪个岛屿维度"。
     *
     * <p>为什么需要：入口解析发生在建岛**之前**。玩家第一次点"海岛"时，
     * 他还没有岛，{@code entryFor} 会判成"回大厅" —— 于是人进了大厅而不是海岛。
     * 所以 {@link #visit} 先把意图登记在这里，解析时优先采纳。
     */
    private final Map<java.util.UUID, String> pendingDestination = new java.util.concurrent.ConcurrentHashMap<>();

    /** 主岛屿维度（配置里 defaultType 对应的那个），用于兼容旧调用。 */
    private final Type primary;

    public IslandService(SubServer skyblockServer, IslandConfig config,
                         SubServer.Entry hubEntry, Map<String, SubServer.Entry> islandEntries) {
        this.server = skyblockServer;
        this.config = config;
        this.hubEntry = hubEntry;

        for (Map.Entry<String, SubServer.Entry> e : islandEntries.entrySet()) {
            String typeId = e.getKey();
            SubServer.Entry entry = e.getValue();
            types.put(typeId, new Type(typeId, entry, new IslandManager(skyblockServer, entry, config)));
        }
        if (types.isEmpty()) {
            throw new IllegalStateException("空岛服没有任何岛屿维度");
        }
        this.primary = types.containsKey(config.defaultType)
                ? types.get(config.defaultType)
                : types.values().iterator().next();
    }

    // ------------------------------------------------------------------
    // 访问
    // ------------------------------------------------------------------

    /**
     * 重建丢失了方块实体的容器。
     *
     * <p>做法是把方块先变成空气再变回来 —— 区块的 {@code setBlockState} 会在
     * 这一步重新创建方块实体。
     *
     * <p><b>为什么需要：</b>方块实体是在 setBlock 时由区块创建的。如果那次调用
     * 赶上区块状态异常的时机（刚铺完地形、区块正从磁盘载入），可能只留下方块本体
     * 而没有实体。后果非常隐蔽 —— {@code ChestBlock.useWithoutItem} 在没有实体时
     * **依然返回 SUCCESS**（手会挥、界面不弹），玩家看到的就是"右键毫无反应"，
     * 而且用"返回值是否成功"根本测不出来。
     *
     * @return true 表示修好了（玩家需要再点一次）
     */
    private static boolean repairContainerBlockEntity(
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState state) {
        try {
            var block = state.getBlock();
            if (!(block instanceof net.minecraft.world.level.block.BaseEntityBlock)) {
                return false;   // 本来就不带方块实体，不用修
            }
            HubSuite.logger().warn("容器 {} @ {} 缺少方块实体，正在重建",
                    block.getName().getString(), pos);
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.setBlockAndUpdate(pos, state);
            boolean fixed = level.getBlockEntity(pos) != null;
            HubSuite.logger().warn("重建{}：{} @ {}",
                    fixed ? "成功" : "失败", block.getName().getString(), pos);
            return fixed;
        } catch (Throwable t) {
            HubSuite.logger().error("重建容器方块实体失败 @ {}", pos, t);
            return false;
        }
    }

    /** 上一次看到的"玩家打开的容器"（用于诊断容器为什么开不了）。 */
    private static final Map<java.util.UUID, String> LAST_CONTAINER =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static int containerWatchTicker;

    /**
     * 诊断：观察每个玩家的 containerMenu 变化。
     *
     * <p>这条日志能一次性回答"服务端到底有没有把容器打开"——
     * 用户反馈"右键箱子毫无反应"，而方块状态、方块实体、保护判定、
     * 事件链全部正常，所以必须直接看 openMenu 的结果。
     *
     * <p>只在**变化时**打印，平时零开销。
     */
    private static void watchContainers(net.minecraft.server.MinecraftServer server) {
        // 每 tick 都查：容器"开了又立刻被关掉"在 5 tick 的间隔里会被漏掉
        containerWatchTicker++;
        for (var player : server.getPlayerList().getPlayers()) {
            String now = player.containerMenu.getClass().getSimpleName()
                    + "#" + player.containerMenu.containerId;
            String before = LAST_CONTAINER.put(player.getUUID(), now);
            if (before != null && !before.equals(now)) {
                HubSuite.logger().info("容器变化：{} {} -> {}（维度 {}）",
                        player.getName().getString(), before, now,
                        player.level().dimension().identifier());
            }
        }
    }

    /** 空岛服大厅维度。 */
    public SubServer.Entry hubEntry() {
        return hubEntry;
    }

    /** 兼容旧调用：主岛屿维度。 */
    public IslandManager manager() {
        return primary.manager();
    }

    public SubServer server() {
        return server;
    }

    public IslandConfig config() {
        return config;
    }

    /** 全部岛型。 */
    public java.util.Collection<Type> types() {
        return java.util.Collections.unmodifiableCollection(types.values());
    }

    /** 按岛型取。 */
    public Optional<Type> type(String typeId) {
        return Optional.ofNullable(types.get(typeId));
    }

    /** 玩家当前所在维度对应的岛型（不在岛屿维度里则为空）。 */
    public Optional<Type> typeOf(Level level) {
        for (Type type : types.values()) {
            if (type.entry().dimension().equals(level.dimension())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /** 跨全部岛型查玩家的岛。 */
    public Optional<IslandManager.Island> islandAnywhere(java.util.UUID uuid) {
        for (Type type : types.values()) {
            Optional<IslandManager.Island> found = type.manager().islandOf(uuid);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /** 玩家是不是在空岛服的任意维度里（大厅或岛屿）。 */
    public boolean isSkyblock(Level level) {
        return server.owns(level.dimension());
    }

    /** 玩家是不是在某个岛屿维度里（不含大厅）。 */
    public boolean isIslandLevel(Level level) {
        return typeOf(level).isPresent();
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    public void register() {
        // 1) 入口解析：决定玩家进哪个维度
        server.setEntryResolver((player, sub) -> {
            // 0) 刚刚用 /island 或界面明确指定了目的地 → 优先
            String wanted = pendingDestination.remove(player.getUUID());
            if (HUB_MARKER.equals(wanted)) {
                return hubEntry;
            }
            if (wanted != null) {
                Type type = types.get(wanted);
                if (type != null) {
                    return type.entry();
                }
            }

            // 1) 玩家上次在某个岛屿维度 → 回那座岛
            var current = player.level().dimension();
            Optional<SubServer.Entry> staying = sub.entryOf(current);
            if (staying.isPresent() && !staying.get().id().equals(hubEntry.id())) {
                return staying.get();
            }
            // 2) 有岛但不在岛上（例如从生存服切过来）→ 回他最近玩的那种岛
            for (Type type : types.values()) {
                if (type.manager().islandOf(player.getUUID()).isPresent()) {
                    return type.entry();
                }
            }
            // 3) 没有岛 → 先去大厅
            return hubEntry;
        });

        // 注意：下面两个监听器都要用**目标维度**判断岛型。
        // 玩家实体此刻还在旧维度里（player.level() 是旧的），
        // 用它判断会把"要进海岛"误判成"在大厅"。

        // 2) 入场：要进岛屿维度但还没岛 → 现场建一座
        PlayerRouter.addEntryListener((player, world, level) -> {
            Optional<Type> type = typeOf(level);
            if (type.isEmpty()) {
                return;   // 大厅，不用建岛
            }
            IslandManager.Island island;
            Optional<IslandManager.Island> existing =
                    type.get().manager().islandOf(player.getUUID());
            if (existing.isPresent()) {
                island = existing.get();
            } else {
                island = createFor(player, type.get().id());
            }
            // 地形可能是延迟铺的（海岛），落岛前补上
            type.get().manager().ensureTerrain(island);
        });

        // 3) 出生点解析：要落进岛屿维度就落到自己岛上
        PlayerRouter.addSpawnResolver((player, target, level) -> {
            if (!(target instanceof SubServer sub) || !sub.id().equals(server.id())) {
                return null;
            }
            SubServer.Entry destination = sub.entryFor(player);
            Optional<Type> type = typeOf(destination.level());
            if (type.isEmpty()) {
                return null;   // 大厅：用维度自己的出生点
            }
            Optional<IslandManager.Island> island = type.get().manager().islandOf(player.getUUID());
            if (island.isEmpty()) {
                return null;
            }
            Vec3 spawn = type.get().manager().spawnOf(island.get());
            return new PlayableWorld.SpawnPoint(spawn.x, spawn.y, spawn.z, 0.0F, 0.0F);
        });

        // 4) 建造保护
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> {
            if (!isSkyblock(level) || !(player instanceof ServerPlayer serverPlayer)) {
                return true;
            }
            if (!canBuildHere(serverPlayer, pos)) {
                serverPlayer.sendSystemMessage(Component.literal("\u00A7c这不是你的岛屿区域，无法破坏。"));
                return false;
            }
            return true;
        });

        UseBlockCallback.EVENT.register((Player player, Level level, InteractionHand hand, BlockHitResult hit) -> {
            // 诊断：无条件记录（之前那版写在两个提前 return 之后，
            // 生存服/空手的情况根本记不到，白排查了一轮）
            if (player instanceof ServerPlayer serverPlayer) {
                HubSuite.logger().info(
                        "[交互诊断] IslandService 判定：{} 是否空岛={} 空手={}（维度 {}，目标 {}）",
                        serverPlayer.getName().getString(), isSkyblock(level),
                        player.getItemInHand(hand).isEmpty(),
                        level.dimension().identifier(), hit.getBlockPos());
            }
            if (!isSkyblock(level) || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.PASS;
            }
            // 只拦"放置/使用"类交互；空手右键仍然放行（避免连门都开不了）
            if (player.getItemInHand(hand).isEmpty()) {
                return InteractionResult.PASS;
            }

            /*
             * 对**容器类方块**（箱子/工作台/熔炉…）每次右键都记一条日志。
             *
             * 用户反复反馈"对着箱子右键打不开"，而自检里箱子是能打开的 ——
             * 说明问题只可能出在真实客户端这条路径上。这条日志能一次性区分：
             *   · 被岛屿保护拦下（会打印"拦下"并给出坐标与手持物）
             *   · 压根没进到模组的回调（那就一条都不打印 → 问题在别处）
             */
            var clicked = level.getBlockState(hit.getBlockPos());
            boolean container = clicked.is(net.minecraft.world.level.block.Blocks.CHEST)
                    || clicked.is(net.minecraft.world.level.block.Blocks.TRAPPED_CHEST)
                    || clicked.is(net.minecraft.world.level.block.Blocks.BARREL)
                    || clicked.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                    || clicked.is(net.minecraft.world.level.block.Blocks.FURNACE);
            if (container) {
                /*
                 * 自愈：容器方块**实体丢了**就现场重建。
                 *
                 * 为什么会丢：方块实体是在 setBlock 时由区块创建的，
                 * 如果那次调用发生在区块状态异常的时机（例如地形刚铺完、
                 * 区块正在从磁盘载入），可能只留下了方块本体而没有实体。
                 * 后果很隐蔽：ChestBlock.useWithoutItem 在没有实体时
                 * **依然返回 SUCCESS**（手会挥、但什么界面都不弹），
                 * 玩家看到的就是"右键毫无反应"。
                 *
                 * 这里在交互时顺手检查并重建一次，老存档也能自动修好。
                 */
                if (level instanceof ServerLevel serverLevel
                        && serverLevel.getBlockEntity(hit.getBlockPos()) == null) {
                    if (repairContainerBlockEntity(serverLevel, hit.getBlockPos(), clicked)) {
                        serverPlayer.sendSystemMessage(Component.literal(
                                "\u00A7e这个容器的数据丢失了，已为你重建，请再点一次。"));
                        return InteractionResult.SUCCESS;
                    }
                }
                boolean allowed = canBuildHere(serverPlayer, hit.getBlockPos());
                HubSuite.logger().info(
                        "右键容器：{} 点 {} @ {} 维度 {}｜手持 {}｜判定 {}｜"
                                + "上方 {}｜方块实体 {}｜受阻 {}",
                        serverPlayer.getName().getString(),
                        clicked.getBlock().getName().getString(),
                        hit.getBlockPos(),
                        level.dimension().identifier(),
                        player.getItemInHand(hand).getItem(),
                        allowed ? "放行" : "被保护拦下",
                        level.getBlockState(hit.getBlockPos().above()).getBlock().getName().getString(),
                        level.getBlockEntity(hit.getBlockPos()),
                        net.minecraft.world.level.block.ChestBlock.isChestBlockedAt(
                                level, hit.getBlockPos()));
            }

            if (!canBuildHere(serverPlayer, hit.getBlockPos())) {
                // 记日志：用户反馈"对着箱子右键打不开"时，这是唯一能区分
                // "被保护拦下"和"根本没走到这里"的证据。
                HubSuite.logger().info("右击被岛屿保护拦下：{} @ {} 维度 {}（手持 {}）",
                        serverPlayer.getName().getString(), hit.getBlockPos(),
                        level.dimension().identifier(),
                        player.getItemInHand(hand).getItem());
                serverPlayer.sendSystemMessage(Component.literal("\u00A7c这不是你的岛屿区域，无法放置。"));
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });

        // 5) 在大厅放一个"选择岛屿"假人
        spawnHubNpc();

        // 每个 tick 尝试补铺"玩家已在岛上但地形还没铺好"的岛。
        // 建岛时区块必然未加载，没有这个重试的话玩家会掉进海里/虚空（实测踩过）。
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                server -> types.values().forEach(t -> t.manager().tickPendingTerrain()));

        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            saveAll();
            types.values().forEach(t -> t.manager().clearPending());
        });
    }

    /**
     * 在空岛服大厅放一个选岛假人。
     *
     * <p>假人由 {@link cn.dreamgary.hubsuite.npc.NpcManager} 生成 ——
     * 它会等服务端起来（维度都加载好）再放，这里只登记意图。
     */
    private void spawnHubNpc() {
        var npcConfig = config.hubNpc == null ? new IslandConfig.HubNpcConfig() : config.hubNpc;
        if (!npcConfig.enabled) {
            return;
        }
        cn.dreamgary.hubsuite.npc.NpcManager.registerMenuNpc(
                hubEntry.level(),
                "hub_island",
                npcConfig.toNpcConfig(),
                player -> IslandMenu.open(player, this, HubSuite.worlds()));
        HubSuite.logger().info("空岛服大厅将生成选岛假人 '{}'（位置 {}, {}, {}）",
                npcConfig.name, npcConfig.x, npcConfig.y, npcConfig.z);
    }

    /** 按玩家所在维度判断能不能建造（大厅永远允许）。 */
    /** 供自检调用：某个位置的交互是否会被放行。 */
    public boolean canBuildAt(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return canBuildHere(player, pos);
    }

    private boolean canBuildHere(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        Optional<Type> type = typeOf(player.level());
        if (type.isEmpty()) {
            return true;   // 大厅
        }
        return type.get().manager().canBuild(player, pos);
    }

    /** 保存全部岛型的归属记录。 */
    public void saveAll() {
        types.values().forEach(t -> t.manager().save());
    }

    // ------------------------------------------------------------------
    // 玩家侧操作
    // ------------------------------------------------------------------

    /**
     * 取（必要时创建）玩家在指定岛型上的岛，**不传送**。
     *
     * <p>入场流程里必须用这个：{@code PlayerRouter} 会先建岛、再解析出生点、
     * 最后统一传送一次。这里如果也传送，就会出现"传送两次、落点还不一致"。
     */
    public IslandManager.Island createFor(ServerPlayer player, String typeId) {
        Type type = typeId == null ? primary : types.get(typeId);
        if (type == null) {
            type = primary;
        }
        // 注意：这里**不要**调 diagnoseSpawn。
        // 它会 getBlockState 读岛屿方块，而此时玩家还在旧维度、目标区块必然未加载
        // → 同步生成整片区块 → 主线程卡死（就是看门狗强杀那条路径，实测踩过两次）。
        // 诊断只在显式调试时用，不进正常建岛流程。
        return type.manager().getOrCreate(
                player.getUUID(), player.getName().getString(), type.id());
    }

    /** 取（必要时创建）玩家的岛，并把玩家送到岛上（岛屿维度里才有效）。 */
    public IslandManager.Island home(ServerPlayer player, String typeId) {
        Type type = typeId == null ? typeOf(player.level()).orElse(primary) : types.get(typeId);
        if (type == null) {
            type = primary;
        }
        IslandManager.Island island = type.manager().getOrCreate(
                player.getUUID(), player.getName().getString(), type.id());
        return island;
    }

    /**
     * 把玩家送到指定岛型的岛上（会切维度）。
     *
     * <p>走 {@code PlayerRouter} 而不是直接 teleportTo：这样
     * 入口解析、出生点解析、玩家状态按维度隔离、重生点绑定全都会正常执行。
     */
    public boolean visit(ServerPlayer player, String typeId) {
        Type type = typeId == null ? typeOf(player.level()).orElse(primary) : types.get(typeId);
        if (type == null) {
            player.sendSystemMessage(Component.literal("\u00A7c没有这种岛型。"));
            return false;
        }
        // 先登记意图：入口解析会在建岛之前跑，没有这一步会被判成"回大厅"
        pendingDestination.put(player.getUUID(), type.id());
        // 建岛（没有的话）。地形可能还没铺（海岛是延迟铺的），
        // 在传送前补上 —— 反正马上要传过去，这些区块本来就得加载。
        var island = type.manager().getOrCreate(player.getUUID(), player.getName().getString(), type.id());
        type.manager().ensureTerrain(island);
        return PlayerRouter.sendTo(player, server);
    }

    /** 把玩家送到空岛服大厅。 */
    public boolean sendToHub(ServerPlayer player) {
        // 明确表示"这次不要去岛屿维度"，否则入口解析会把有岛的玩家又送回岛上
        pendingDestination.put(player.getUUID(), HUB_MARKER);
        return PlayerRouter.sendTo(player, server);
    }

    /** 把玩家送到他自己的岛（当前维度的那座）。 */
    public boolean teleportHome(ServerPlayer player, IslandManager.Island island) {
        Type type = types.get(island.type);
        if (type == null) {
            type = primary;
        }
        Vec3 spawn = type.manager().spawnOf(island);
        try {
            player.teleportTo(type.entry().level(), spawn.x, spawn.y, spawn.z,
                    java.util.Set.of(), player.getYRot(), player.getXRot(), false);
            return true;
        } catch (Throwable t) {
            HubSuite.logger().error("传送 {} 到空岛失败", player.getName().getString(), t);
            return false;
        }
    }

    /** 掉虚空保护：由 ServerRulesEngine 的 tick 调用。 */
    public boolean handleVoidFall(ServerPlayer player) {
        Optional<Type> type = typeOf(player.level());
        if (type.isEmpty()) {
            return false;
        }
        Optional<IslandManager.Island> island = type.get().manager().islandOf(player.getUUID());
        if (island.isEmpty()) {
            // 没有岛（异常情况）：至少送回大厅，别让他一直掉
            sendToHub(player);
            return true;
        }
        teleportHome(player, island.get());
        player.sendSystemMessage(Component.literal("\u00A7e你掉进了虚空，已送回你的岛。"));
        return true;
    }

    /**
     * 诊断：打印"代码认为的岛"与"该位置实际方块"。
     *
     * <p>用于排查"卡在树里""箱子说不是我的区域"这类坐标对不上的问题。
     */
    public void diagnoseSpawn(ServerPlayer player, Type type, IslandManager.Island island) {
        try {
            IslandManager manager = type.manager();
            var center = manager.plotCenter(island.plotX, island.plotZ);
            var spawn = manager.spawnOf(island);
            var spawnBlock = net.minecraft.core.BlockPos.containing(spawn.x, spawn.y, spawn.z);
            ServerLevel level = type.entry().level();

            HubSuite.logger().info("=== 落脚点诊断：{} 的岛（岛型 {}，方格 {},{}）===",
                    player.getName().getString(), type.id(), island.plotX, island.plotZ);
            HubSuite.logger().info("  记录：岛中心 {} / 落脚点 {}", center, spawnBlock);
            HubSuite.logger().info("  实际方块：中心={} 落脚点={} 下方={} 上方={}",
                    level.getBlockState(center).getBlock().getName().getString(),
                    level.getBlockState(spawnBlock).getBlock().getName().getString(),
                    level.getBlockState(spawnBlock.below()).getBlock().getName().getString(),
                    level.getBlockState(spawnBlock.above()).getBlock().getName().getString());
        } catch (Throwable t) {
            HubSuite.logger().warn("落脚点诊断失败：{}", t.toString());
        }
    }

}
