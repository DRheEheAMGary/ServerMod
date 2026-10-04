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
    private MinecraftServer server;

    public WorldsManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        // 持续刷新"玩家 → 目标存档"的映射，保证任何时刻保存都落到正确的子服
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                PlayerDataRouter::refreshPending);
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
                    // 虚空世界没有地面，给大厅铺一块平台，否则玩家一进去就掉虚空
                    int blocks = SpawnPlatform.build(hub.level(), hub.spawn(),
                            Math.max(6, config.lobby.platformRadius));
                    HubSuite.logger().info("空岛服大厅出生平台已生成：{} 个方块", blocks);
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

    private void onServerStopping(MinecraftServer server) {
        saveAll(true);
        subServers.values().forEach(SubServer::close);
        subServers.clear();
        if (lobby != null) {
            lobby.close();
            lobby = null;
        }
        RulesManager.clear();
        PlayerDataRouter.clear();
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
        var dimension = player.level().dimension();
        if (lobby != null && lobby.level().dimension().equals(dimension)) {
            return Optional.of(lobby);
        }
        for (SubServer sub : subServers.values()) {
            if (sub.level().dimension().equals(dimension)) {
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
