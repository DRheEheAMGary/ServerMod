package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.ConfigManager;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多世界引擎的总入口：负责在服务端启动后装载大厅与全部子服，
 * 并在关闭时把一切安全落盘。
 *
 * <p>玩家所在子服的判定不需要额外记录：玩家实体本身就在某个维度里，
 * 而每个维度唯一对应一个 {@link PlayableWorld}。
 */
public final class WorldsManager {

    private final ConfigManager configManager;
    private final Map<String, SubServer> subServers = new LinkedHashMap<>();
    private Lobby lobby;
    /**
     * 服务端引用。
     *
     * <p>必须是 volatile：认证服务的异步回调（非主线程）会通过
     * {@link #server()} 读它，用来把结果 execute 回主线程。
     * 不保证可见性的话可能读到 null 或陈旧值。
     */
    private volatile MinecraftServer server;

    public WorldsManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        // 关服分两阶段，顺序很关键：
        //   STOPPING = 原版 stopServer() 的**开头**，此时只是我们主动落盘；
        //   STOPPED  = 原版 stopServer() **跑完之后**，这时才能拆路由/关存档。
        //
        // 踩过的坑：原来把清理全放在 STOPPING，结果原版随后的
        // playerList.saveAll() 因为路由已被清空，把每个在线玩家的最终状态
        // 写进了主世界存档（world/players/data），而各子服的数据停留在
        // 上一次登出时的内容 —— 玩家最后一段游戏成果永久丢失。
        // 同理，提前从 MinecraftServer.levels 摘掉维度，会让原版的
        // "等待区块排空 → saveAllChunks → level.close()" 完全看不到这些维度，
        // 区块句柄一直开到 JVM 退出。
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
        // 持续刷新"玩家 → 目标存档"的映射，保证任何时刻保存都落到正确的子服
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                PlayerDataRouter::refreshPending);
        // 海上出生平台要等区块加载，启动时铺不成很正常 —— 每个 tick 补一次
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                server -> SubServer.tickPendingPlatforms());
        // 原版出生点搜索（最多 121 区块的同步螺旋）在冷存档上会卡死主线程 ——
        // 加载阶段先跳过，等区块就绪后在这里补算并写回缓存
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                server -> SubServer.tickPendingSpawns());
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    private void onServerStarted(MinecraftServer server) {
        this.server = server;
        PlayerDataRouter.setServer(server);
        HubSuiteConfig config = configManager.config();

        long start = System.currentTimeMillis();
        try {
            lobby = Lobby.load(server, config.lobby);
            HubSuite.logger().info("大厅已加载：{}（{}）", lobby.level().dimension().identifier(), lobby.save().root());
        } catch (Exception e) {
            HubSuite.logger().error("大厅加载失败，HubSuite 功能将不可用！", e);
            return;
        }

        for (HubSuiteConfig.SubServerConfig subConfig : config.servers) {
            if (!subConfig.enabled) {
                HubSuite.logger().info("子服 '{}' 已在配置中禁用，跳过。", subConfig.id);
                continue;
            }
            try {
                SubServer sub = SubServer.load(server, subConfig);
                // 空岛服是一个"多维度子服"：主维度当大厅，另外加两个岛屿维度。
                // 每个维度都是独立存档 —— PlayerDataStorage 按存档分目录，
                // 共用一份的话三个维度的背包/经验会互相覆盖。
                if ("skyblock".equals(subConfig.id)) {
                    var hub = sub.addDimension("hub", HubSuiteConfig.WorldKind.VOID, null,
                            subConfig.displayName + " §7大厅");
                    // 虚空世界没有地面，给大厅铺一块平台，否则玩家一进去就掉虚空。
                    // 此刻区块大概率还没加载 —— 绝不能就地同步生成（冷存档上
                    // SpawnPlatform 里的 getBlockState 会阻塞主线程到看门狗强杀）。
                    // 没铺成就登记进"待铺"列表，由每 tick 的重试补上。
                    var hubSpawn = hub.spawn();
                    int hubRadius = Math.max(6, config.lobby.platformRadius);
                    int blocks = SpawnPlatform.build(hub.level(), hubSpawn, hubRadius);
                    if (blocks == SpawnPlatform.CHUNKS_NOT_READY) {
                        SubServer.queueSpawnPlatform(hub.level(), hubSpawn, hubRadius, "skyblock/hub");
                        HubSuite.logger().info("空岛服大厅出生平台等区块加载后补铺");
                    } else {
                        HubSuite.logger().info("空岛服大厅出生平台已生成：{} 个方块", blocks);
                    }
                    sub.addDimension("classic", HubSuiteConfig.WorldKind.VOID, null,
                            "§a经典空岛");
                    // 海岛维度：一整片自然生成的海洋（含沉船、海底废墟、珊瑚礁），
                    // 岛由玩家创建时"点"进海里。
                    sub.addDimension("ocean", HubSuiteConfig.WorldKind.NORMAL, null,
                            "§b海岛", WorldFactory.oceanGenerator(server));
                }
                subServers.put(sub.id(), sub);
                HubSuite.logger().info("子服已加载：{} -> {}（存档 {}）",
                        sub.id(), sub.level().dimension().identifier(), sub.save().saveName());
            } catch (Exception e) {
                HubSuite.logger().error("子服 '{}' 加载失败，已跳过。", subConfig.id, e);
            }
        }

        HubSuite.logger().info("多世界引擎就绪：大厅 1 个，子服 {} 个，耗时 {} ms。",
                subServers.size(), System.currentTimeMillis() - start);
    }

    /**
     * 关服第一阶段：**只落盘，不拆任何东西**。
     *
     * <p>原版 {@code stopServer()} 在这之后还要执行
     * {@code playerList.saveAll()}、{@code saveAllChunks(...)} 与
     * {@code level.close()}。我们必须让路由表、维度映射、存档句柄
     * 在那之前保持有效，否则那些收尾动作会写到错误的地方。
     */
    private void onServerStopping(MinecraftServer server) {
        saveAll(true);
        // 任务进度也落盘（它是独立的小 JSON，不随子服存档走）
        try {
            var quests = HubSuite.quests();
            if (quests != null) {
                quests.save();
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("保存任务进度失败：{}", t.toString());
        }
        HubSuite.logger().info("多世界引擎：已主动保存全部子服，等待原版收尾。");
    }

    /**
     * 关服第二阶段：原版收尾完成后，才关闭存档、拆掉路由。
     */
    private void onServerStopped(MinecraftServer server) {
        subServers.values().forEach(SubServer::close);
        subServers.clear();
        if (lobby != null) {
            lobby.close();
            lobby = null;
        }
        // 暂存的内存状态（背包等）也要清 —— 不然同一 JVM 内重启会残留上一局的数据
        cn.dreamgary.hubsuite.world.PlayerStateStash.clear();
        RulesManager.clear();
        PlayerDataRouter.clear();
        this.server = null;
        HubSuite.logger().info("多世界引擎已关闭，全部子服已保存。");
    }

    /** 保存全部场所（可用 /hub save 手动触发）。 */
    public boolean saveAll(boolean flush) {
        boolean ok = true;
        if (lobby != null) {
            lobby.save(flush);
            lobby.rules().persist();
        }
        for (SubServer sub : subServers.values()) {
            try {
                sub.save(flush);
                sub.rules().persist();
            } catch (Exception e) {
                HubSuite.logger().error("保存子服 '{}' 失败", sub.id(), e);
                ok = false;
            }
        }
        return ok;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public Optional<Lobby> lobby() {
        return Optional.ofNullable(lobby);
    }

    public Optional<SubServer> subServer(String id) {
        return Optional.ofNullable(subServers.get(id));
    }

    public Collection<SubServer> subServers() {
        return List.copyOf(subServers.values());
    }

    public List<PlayableWorld> allWorlds() {
        List<PlayableWorld> worlds = new ArrayList<>();
        if (lobby != null) {
            worlds.add(lobby);
        }
        worlds.addAll(subServers.values());
        return worlds;
    }

    /** 玩家当前所在的场所（按维度反查）。 */
    public Optional<PlayableWorld> worldOf(ServerPlayer player) {
        return worldOfDimension(player.level().dimension());
    }

    /**
     * 某个维度属于哪个场所。
     *
     * <p><b>必须遍历子服的全部维度，而不只是主维度。</b>
     * 空岛服有大厅/经典/海岛三个维度，玩家在海岛维度时如果只比对
     * {@code sub.level()}（恒为 primary），会判定成"不在任何场所"——
     * 后果是 {@code PlayerRouter} 认为无需暂存状态，玩家**切服时直接丢背包**
     * （实测自检发现的）。
     */
    public Optional<PlayableWorld> worldOfDimension(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        if (lobby != null && lobby.level().dimension().equals(dimension)) {
            return Optional.of(lobby);
        }
        for (SubServer sub : subServers.values()) {
            if (sub.owns(dimension)) {
                return Optional.of(sub);
            }
        }
        return Optional.empty();
    }

    public Optional<PlayableWorld> worldById(String id) {
        if (Lobby.ID.equalsIgnoreCase(id)) {
            return lobby().map(w -> w);
        }
        return subServer(id).map(w -> w);
    }

    public boolean isReady() {
        return lobby != null;
    }

    public MinecraftServer server() {
        return server;
    }
}
