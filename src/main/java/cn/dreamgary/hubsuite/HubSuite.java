package cn.dreamgary.hubsuite;

import cn.dreamgary.hubsuite.auth.AuthManager;
import cn.dreamgary.hubsuite.auth.AuthService;
import cn.dreamgary.hubsuite.command.HubCommand;
import cn.dreamgary.hubsuite.config.ConfigManager;
import cn.dreamgary.hubsuite.ui.DialogRouter;
import cn.dreamgary.hubsuite.world.PlayerRouter;
import cn.dreamgary.hubsuite.world.WorldsManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HubSuite 主入口。
 *
 * <p>本项目是<strong>纯服务端</strong>模组：所有功能都跑在 dedicated server 上，
 * 客户端使用原版即可进服（不安装也能玩）。
 *
 * <p>子系统一览（按开发阶段逐步接入）：
 * <ul>
 *   <li>{@code config} —— JSON 配置</li>
 *   <li>{@code world} —— 多世界引擎（每个子服一个独立存档，规则与玩家数据天然隔离）</li>
 *   <li>{@code command} —— {@code /hub} 命令族</li>
 *   <li>Phase 2 —— {@code auth} 对话框注册 / 登录</li>
 *   <li>Phase 3 —— {@code lobby} 假人大厅 NPC</li>
 *   <li>Phase 4 —— {@code island} 空岛玩法</li>
 *   <li>Phase 5 —— {@code integration} HuskHomes / Text Placeholder API / LuckPerms 适配</li>
 * </ul>
 */
public final class HubSuite implements ModInitializer {

    public static final String MOD_ID = "hubsuite";
    public static final String MOD_NAME = "HubSuite";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static HubSuite instance;

    private ConfigManager configManager;
    private WorldsManager worldsManager;
    private AuthService authService;
    private AuthManager authManager;
    private cn.dreamgary.hubsuite.npc.NpcManager npcManager;
    private cn.dreamgary.hubsuite.feature.ServerRulesEngine rulesEngine;
    private cn.dreamgary.hubsuite.feature.LoginLockdown lockdown;
    private cn.dreamgary.hubsuite.feature.PermissionService permissions;
    private cn.dreamgary.hubsuite.integration.HuskHomesIntegration huskHomes;
    private cn.dreamgary.hubsuite.integration.PlaceholderService placeholders;
    private cn.dreamgary.hubsuite.feature.CommandGuard commandGuard;
    private cn.dreamgary.hubsuite.island.IslandService islands;

    @Override
    public void onInitialize() {
        instance = this;
        LOGGER.info("{} 正在初始化……", MOD_NAME);
        logEnvironment();

        configManager = new ConfigManager();
        configManager.loadOrCreate();

        worldsManager = new WorldsManager(configManager);
        worldsManager.register();

        authService = new AuthService(
                configManager.configDir(),
                configManager.config().auth,
                // 认证异步结果回到服务端主线程
                task -> {
                    var server = worldsManager.server();
                    if (server != null) {
                        server.execute(task);
                    } else {
                        task.run();
                    }
                });
        authManager = new AuthManager(authService, worldsManager);
        authManager.register();
        DialogRouter.bind(authManager, authService, worldsManager);

        npcManager = new cn.dreamgary.hubsuite.npc.NpcManager(worldsManager, authManager);
        npcManager.register();

        rulesEngine = new cn.dreamgary.hubsuite.feature.ServerRulesEngine(worldsManager);
        rulesEngine.register();
        // 出生点解析器：多维度子服要把**具体维度**也传进去 ——
        // 坐标记忆是按维度存的（大厅/经典/海岛的场所 id 都是 skyblock）
        PlayerRouter.addSpawnResolver((player, target, level) ->
                target instanceof cn.dreamgary.hubsuite.world.SubServer sub
                        ? rulesEngine.resolveEntry(player, sub, level.dimension())
                        : target.spawn());

        // 未登录锁定 + 命令闸门 + 权限
        permissions = new cn.dreamgary.hubsuite.feature.PermissionService();
        lockdown = new cn.dreamgary.hubsuite.feature.LoginLockdown(worldsManager, authManager);
        lockdown.register();
        commandGuard = new cn.dreamgary.hubsuite.feature.CommandGuard(worldsManager, lockdown, permissions);
        cn.dreamgary.hubsuite.feature.CommandGuardHolder.set(commandGuard);

        // 集成：HuskHomes 与占位符
        huskHomes = new cn.dreamgary.hubsuite.integration.HuskHomesIntegration(worldsManager);
        placeholders = new cn.dreamgary.hubsuite.integration.PlaceholderService(worldsManager, huskHomes);

        // 任务系统不依赖世界，初始化阶段就能建好
        try {
            questManager = new cn.dreamgary.hubsuite.quest.QuestManager(
                    configManager.config().quests);
            cn.dreamgary.hubsuite.quest.QuestEvents.register();
            logger().info("任务系统已就绪：{} 个任务（里程碑 {} / 交付 {} / 每日 {}）",
                    questManager.config().quests.size(),
                    questManager.config().ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.MILESTONE).size(),
                    questManager.config().ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.DELIVER).size(),
                    questManager.config().ofKind(cn.dreamgary.hubsuite.quest.Quest.Kind.DAILY).size());
        } catch (Throwable t) {
            logger().error("任务系统初始化失败，任务功能将不可用", t);
        }

        // 服务端启动后再做依赖于世界的初始化（空岛、占位符注册）
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            worldsManager.subServer(skyblockServerId()).ifPresent(sub -> {
                // 空岛服 = 大厅 + 两种岛屿维度（各自独立存档，玩家数据互不覆盖）
                var hubEntry = sub.entry("hub").orElse(null);
                java.util.Map<String, cn.dreamgary.hubsuite.world.SubServer.Entry> islandEntries =
                        new java.util.LinkedHashMap<>();
                for (String typeId : new String[]{"classic", "ocean"}) {
                    sub.entry(typeId).ifPresent(e -> islandEntries.put(typeId, e));
                }
                if (hubEntry == null || islandEntries.isEmpty()) {
                    HubSuite.logger().error("空岛服维度不完整（大厅={}，岛屿维度={}），空岛功能禁用。",
                            hubEntry != null, islandEntries.keySet());
                    return;
                }
                islands = new cn.dreamgary.hubsuite.island.IslandService(
                        sub, islandsConfig(), hubEntry, islandEntries);
                islands.register();
                // 空岛系统可能注册了新的菜单假人（选岛假人），
                // 而 NpcManager 的生成时机在本事件之前 —— 这里补一次。
                if (npcManager != null) {
                    npcManager.spawnRegisteredMenus(server);
                }
                HubSuite.logger().info("空岛系统已启用（子服 {}，{} 座岛）。",
                        sub.id(), islands.manager().count());
            });
            placeholders.registerWithTextPlaceholderApi();
            huskHomes.logReport();
            placeholders.logReport();
            commandGuard.logPolicy();
        });

        // 指令分工：/hub 只管传送，/menu 开界面，/auth 管账号，/island 管空岛
        //
        // 注意：所有指令都必须在**模组初始化阶段**注册。
        // CommandRegistrationCallback 在服务端启动前就触发完了，
        // 放到 SERVER_STARTED 里注册的指令根本进不了 dispatcher（实测踩过）。
        // /island 需要 IslandService，而后者要等服务端起来才有 ——
        // 所以这里用 supplier 延迟解析，执行时再去拿。
        new cn.dreamgary.hubsuite.island.IslandCommand(() -> islands, permissions).register();
        new HubCommand(worldsManager, configManager, authService, authManager).register();
        cn.dreamgary.hubsuite.command.MenuCommand.register();
        new cn.dreamgary.hubsuite.command.AuthCommand(worldsManager, authService, authManager).register();

        LOGGER.info("{} 初始化完成，等待服务端启动后装载世界。", MOD_NAME);
    }

    /** 启动时打印一次环境报告，便于运维排查（Phase 5 会扩展成完整的适配报告）。 */
    private static void logEnvironment() {
        FabricLoader loader = FabricLoader.getInstance();
        LOGGER.info("  Minecraft 版本: {}",
                loader.getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        LOGGER.info("  Fabric Loader : {}",
                loader.getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        LOGGER.info("  运行环境      : {}", loader.getEnvironmentType());
        LOGGER.info("  软依赖探测    : huskhomes={}, placeholder-api={}, luckperms={}, fabric-permissions-api-v0={}",
                isLoaded("huskhomes"), isLoaded("placeholder-api"), isLoaded("luckperms"), isLoaded("fabric-permissions-api-v0"));
    }

    private static boolean isLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static HubSuite get() {
        return instance;
    }

    public static ConfigManager configManager() {
        return instance == null ? null : instance.configManager;
    }

    public static WorldsManager worlds() {
        return instance == null ? null : instance.worldsManager;
    }

    /** 任务系统；未初始化时为 null。 */
    public static cn.dreamgary.hubsuite.quest.QuestManager quests() {
        return questManager;
    }

    private static cn.dreamgary.hubsuite.quest.QuestManager questManager;

    public static AuthService auth() {
        return instance == null ? null : instance.authService;
    }

    public static AuthManager authManager() {
        return instance == null ? null : instance.authManager;
    }

    public static cn.dreamgary.hubsuite.npc.NpcManager npcs() {
        return instance == null ? null : instance.npcManager;
    }

    public static cn.dreamgary.hubsuite.feature.ServerRulesEngine rulesEngine() {
        return instance == null ? null : instance.rulesEngine;
    }

    public static cn.dreamgary.hubsuite.island.IslandService islands() {
        return instance == null ? null : instance.islands;
    }

    public static cn.dreamgary.hubsuite.integration.HuskHomesIntegration huskHomes() {
        return instance == null ? null : instance.huskHomes;
    }

    public static cn.dreamgary.hubsuite.integration.PlaceholderService placeholders() {
        return instance == null ? null : instance.placeholders;
    }

    /** 别名，便于自检里短写。 */
    public static cn.dreamgary.hubsuite.integration.HuskHomesIntegration HuskHomes() {
        return huskHomes();
    }

    public static cn.dreamgary.hubsuite.integration.PlaceholderService Placeholders() {
        return placeholders();
    }

    public static cn.dreamgary.hubsuite.feature.PermissionService Permissions() {
        return permissions();
    }

    public static cn.dreamgary.hubsuite.feature.PermissionService permissions() {
        return instance == null ? null : instance.permissions;
    }

    public static cn.dreamgary.hubsuite.feature.CommandGuard commandGuard() {
        return instance == null ? null : instance.commandGuard;
    }

    /** 空岛所在的子服 id（配置里可改，默认 skyblock）。 */
    private String skyblockServerId() {
        return configManager.config().island.serverId;
    }

    private cn.dreamgary.hubsuite.island.IslandConfig islandsConfig() {
        return configManager.config().island;
    }

    public static Logger logger() {
        return LOGGER;
    }
}
