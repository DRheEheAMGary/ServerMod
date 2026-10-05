package cn.dreamgary.hubsuite.npc;

import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.auth.AuthManager;
import cn.dreamgary.hubsuite.lobby.ServerJoinDialog;
import cn.dreamgary.hubsuite.world.Lobby;
import cn.dreamgary.hubsuite.world.SubServer;
import cn.dreamgary.hubsuite.world.WorldsManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大厅 NPC 的总控：生成、维护、处理点击。
 *
 * <p>交互方式（两种都给，降低玩家学习成本）：
 * <ul>
 *   <li><b>右键</b> NPC —— 弹出该子服的确认对话框（显示在线人数、世界类型、规则）</li>
 *   <li><b>左键</b> NPC —— 直接进入（跳过确认）</li>
 * </ul>
 *
 * <p>NPC 由 {@link cn.dreamgary.hubsuite.fake.FakePlayers} 那套机制生成，
 * 本质是"不接网络的真玩家"，所以可见、可点、可被追踪，但不占在线人数、不出现在 Tab。
 */
public final class NpcManager {

    private final WorldsManager worlds;
    private final AuthManager authManager;
    /** 大厅引导假人的实体名。 */
    private static final String MENU_NPC_NAME = "hub_teleport";

    /**
     * 额外注册的菜单假人：{@code "维度id:实体名" → 点击动作}。
     *
     * <p>大厅那个是内置的；空岛服大厅的"选择岛屿"假人由
     * {@link #registerMenuNpc} 在运行时注册进来。
     */
    private static final java.util.Map<String, java.util.function.Consumer<ServerPlayer>> EXTRA_MENUS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 注册一个额外的菜单假人。
     *
     * @param level   放在哪个维度
     * @param name    实体名（唯一）
     * @param config  外观配置
     * @param onClick 点击动作
     */
    public static void registerMenuNpc(net.minecraft.server.level.ServerLevel level,
                                       String name,
                                       HubSuiteConfig.NpcConfig config,
                                       java.util.function.Consumer<ServerPlayer> onClick) {
        EXTRA_MENUS.put(level.dimension().identifier() + ":" + name, onClick);
        PENDING_SPAWNS.add(new PendingMenu(level, name, config, onClick));
    }

    /** 待生成的额外菜单假人（等服务端起来后再放）。 */
    private record PendingMenu(net.minecraft.server.level.ServerLevel level, String name,
                               HubSuiteConfig.NpcConfig config,
                               java.util.function.Consumer<ServerPlayer> onClick) {
    }

    private static final java.util.List<PendingMenu> PENDING_SPAWNS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 取某个假人被点击时要执行的动作。
     *
     * <p><b>必须按"维度 + 假人名"精确查</b>，不能只按维度前缀匹配 ——
     * 同一维度注册两个菜单假人时，前缀匹配会返回任意一个（ConcurrentHashMap
     * 迭代顺序不确定），点哪个假人都开同一个界面。
     */
    private static java.util.function.Consumer<ServerPlayer> actionFor(ServerPlayer player, String npcName) {
        String key = player.level().dimension().identifier() + ":" + npcName;
        return EXTRA_MENUS.get(key);
    }

    private final List<HubNpc> npcs = new ArrayList<>();
    private final Map<UUID, HubNpc> byEntity = new ConcurrentHashMap<>();
    private int tickCounter;

    public NpcManager(WorldsManager worlds, AuthManager authManager) {
        this.worlds = worlds;
        this.authManager = authManager;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> despawnAll());
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);

        UseEntityCallback.EVENT.register(this::onUseEntity);
        AttackEntityCallback.EVENT.register(this::onAttackEntity);
    }

    private void onServerStarted(MinecraftServer server) {
        Lobby lobby = worlds.lobby().orElse(null);
        if (lobby == null) {
            HubSuite.logger().warn("大厅未加载，跳过 NPC 生成。");
            return;
        }
        spawnAll(server, lobby);
    }

    /**
     * 生成大厅的引导假人。
     *
     * <p>只生成**一个**：点击它打开箱子 GUI，在界面里选择要去的服务器。
     * 这样新增子服时不需要再摆一个新的假人，界面里会自动多出一项。
     */
    public void spawnAll(MinecraftServer server, Lobby lobby) {
        despawnAll();

        HubSuiteConfig.NpcConfig config = menuNpcConfig();
        if (config.enabled) {
            HubNpc npc = HubNpc.createMenu(config, MENU_NPC_NAME);
            npc.setLevelAnchor(lobby.spawn());   // 坐标配置是相对出生点的偏移
            if (npc.spawn(server, lobby.level())) {
                npcs.add(npc);
                if (npc.entity() != null) {
                    byEntity.put(npc.entity().getUUID(), npc);
                }
            } else {
                HubSuite.logger().warn("大厅引导假人生成失败。");
            }
        }
        spawnExtraMenus(server);
        // 注意：这里只统计"大厅引导假人"。空岛服的选岛假人由 IslandService
        // 在稍后注册，数量要等 spawnRegisteredMenus 之后才完整。
        HubSuite.logger().info("大厅引导假人已就绪：{} 个（空岛服选岛假人随后生成）。", npcs.size());
    }

    /**
     * 生成运行时注册的额外菜单假人。
     *
     * <p>公开出来是因为**顺序问题**：{@code NpcManager} 与
     * {@code IslandService} 都监听 SERVER_STARTED，而前者先注册、先执行 ——
     * 那时空岛服的选岛假人还没登记。所以空岛系统初始化完要再喊一次。
     */
    public void spawnRegisteredMenus(MinecraftServer server) {
        spawnExtraMenus(server);
        HubSuite.logger().info("全部引导/菜单假人已就绪：{} 个。", npcs.size());
    }

    /**
     * 取某个维度所属场所的出生点（作为假人坐标的偏移基准）。
     *
     * <p>找不到就退回"维度原点上方 64"这种保守值，而不是 null ——
     * 至少不会把假人塞到基岩层里。
     */
    private static cn.dreamgary.hubsuite.world.PlayableWorld.SpawnPoint spawnOf(
            net.minecraft.server.level.ServerLevel level) {
        try {
            var worlds = HubSuite.worlds();
            if (worlds != null) {
                var world = worlds.worldOfDimension(level.dimension());
                if (world.isPresent()) {
                    return world.get().spawn();
                }
            }
        } catch (Throwable t) {
            HubSuite.logger().debug("取维度出生点失败，假人坐标将按绝对坐标处理：{}", t.toString());
        }
        // 退回维度自己的出生点（26.1 是 RespawnData）
        var r = level.getLevelData().getRespawnData();
        var p = r.pos();
        return new cn.dreamgary.hubsuite.world.PlayableWorld.SpawnPoint(
                p.getX() + 0.5, p.getY(), p.getZ() + 0.5, r.yaw(), r.pitch());
    }

    /** 生成运行时注册的额外菜单假人（空岛服大厅的"选择岛屿"）。 */
    private void spawnExtraMenus(MinecraftServer server) {
        if (PENDING_SPAWNS.isEmpty()) {
            return;
        }
        for (PendingMenu pending : PENDING_SPAWNS) {
            try {
                HubNpc npc = HubNpc.createMenu(pending.config(), pending.name());
                // 坐标是相对该维度出生点的偏移 —— 空岛服大厅地板在 Y=100、
                // 出生点在 Y=101，用绝对坐标会让假人埋进地板里（用户实测反馈）。
                npc.setLevelAnchor(spawnOf(pending.level()));
                if (!npc.spawn(server, pending.level())) {
                    HubSuite.logger().warn("菜单假人 '{}' 生成失败。", pending.name());
                    continue;
                }
                npcs.add(npc);
                if (npc.entity() != null) {
                    byEntity.put(npc.entity().getUUID(), npc);
                    EXTRA_MENUS.put(pending.level().dimension().identifier() + ":"
                            + pending.name(), pending.onClick());
                }
            } catch (Throwable t) {
                HubSuite.logger().error("生成菜单假人 '{}' 失败", pending.name(), t);
            }
        }
    }

    /**
     * 引导假人的外观配置。
     *
     * <p>优先用 {@code config.lobby.menuNpc}；没配的话就用一个默认值，
     * 并把三个子服假人原本的位置作为默认坐标参考。
     */
    private HubSuiteConfig.NpcConfig menuNpcConfig() {
        var lobbyConfig = HubSuite.configManager() == null
                ? null : HubSuite.configManager().config().lobby;
        HubSuiteConfig.NpcConfig config = lobbyConfig == null || lobbyConfig.menuNpc == null
                ? new HubSuiteConfig.NpcConfig()
                : lobbyConfig.menuNpc;
        if (config.name == null || config.name.isBlank()) {
            config.name = "\u00A7a\u00A7l点击传送";
        }
        return config;
    }

    public void despawnAll() {
        npcs.forEach(HubNpc::despawn);
        npcs.clear();
        byEntity.clear();
    }

    private void onTick(MinecraftServer server) {
        // 每 10 tick 让 NPC 面向最近的玩家；每 5 秒重建一次索引（防止实体被意外替换）
        tickCounter++;
        if (tickCounter % 10 == 0) {
            npcs.forEach(HubNpc::faceNearestPlayer);
        }
        if (tickCounter % 100 == 0) {
            byEntity.clear();
            for (HubNpc npc : npcs) {
                if (npc.entity() != null) {
                    byEntity.put(npc.entity().getUUID(), npc);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 交互
    // ------------------------------------------------------------------

    private InteractionResult onUseEntity(Player rawPlayer, Level level,
                                          InteractionHand hand, Entity entity, EntityHitResult hitResult) {
        if (!(rawPlayer instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        HubNpc npc = byEntity.get(entity.getUUID());
        if (npc == null) {
            return InteractionResult.PASS;
        }
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.SUCCESS;
        }
        if (authManager != null && !authManager.isAuthenticated(player)) {
            player.sendSystemMessage(Component.literal("\u00A7c请先完成注册或登录。"));
            return InteractionResult.FAIL;
        }
        var action = actionFor(player, npc.npcName());
        if (action != null) {
            HubSuite.logger().info("{} 点击了菜单假人 {}", player.getName().getString(), npc.npcName());
            action.accept(player);
        } else {
            HubSuite.logger().info("{} 点击了引导假人，打开服务器选择界面",
                    player.getName().getString());
            ServerMenu.open(player, worlds);
        }
        return InteractionResult.SUCCESS;
    }

    private InteractionResult onAttackEntity(Player rawPlayer, Level level,
                                             InteractionHand hand, Entity entity, EntityHitResult hitResult) {
        if (!(rawPlayer instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        HubNpc npc = byEntity.get(entity.getUUID());
        if (npc == null) {
            return InteractionResult.PASS;
        }
        if (authManager != null && !authManager.isAuthenticated(player)) {
            HubSuite.logger().info("{} 左键点击引导假人被拒：未认证",
                    player.getName().getString());
            player.sendSystemMessage(Component.literal("\u00A7c请先完成注册或登录。"));
            return InteractionResult.FAIL;
        }
        var action = actionFor(player, npc.npcName());
        if (action != null) {
            HubSuite.logger().info("{} 左键点击了菜单假人 {}", player.getName().getString(), npc.npcName());
            action.accept(player);
        } else {
            HubSuite.logger().info("{} 左键点击了引导假人，打开服务器选择界面",
                    player.getName().getString());
            ServerMenu.open(player, worlds);
        }
        return InteractionResult.SUCCESS;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public List<HubNpc> npcs() {
        return List.copyOf(npcs);
    }

    public HubNpc byTarget(String serverId) {
        return npcs.stream()
                .filter(n -> !n.isMenuNpc() && n.target() != null)
                .filter(n -> n.target().id().equalsIgnoreCase(serverId))
                .findFirst()
                .orElse(null);
    }
}
