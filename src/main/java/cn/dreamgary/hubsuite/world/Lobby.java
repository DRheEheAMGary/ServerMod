package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelData;

import java.io.IOException;
import java.util.List;

/**
 * 大厅：玩家登录成功后所在的地方，也是假人 NPC 站的地方。
 *
 * <p>大厅本身也是"一个独立存档 + 一个独立维度"，与子服走完全相同的代码路径，
 * 所以大厅的规则、存档、玩家数据同样与子服隔离。
 */
public final class Lobby implements PlayableWorld {

    public static final String ID = "lobby";

    private final MinecraftServer server;
    private final HubSuiteConfig.LobbyConfig config;
    private final IsolatedSave save;
    private final ServerRules rules;
    private final ServerLevel level;
    private final PlayableWorld.SpawnPoint spawn;

    private Lobby(MinecraftServer server,
                  HubSuiteConfig.LobbyConfig config,
                  IsolatedSave save,
                  ServerRules rules,
                  ServerLevel level,
                  PlayableWorld.SpawnPoint spawn) {
        this.server = server;
        this.config = config;
        this.save = save;
        this.rules = rules;
        this.level = level;
        this.spawn = spawn;
    }

    public static Lobby load(MinecraftServer server, HubSuiteConfig.LobbyConfig config) throws IOException {
        IsolatedSave save = IsolatedSave.open(
                server,
                config.saveName,
                "HubSuite Lobby",
                GameType.ADVENTURE,
                Difficulty.PEACEFUL,
                true);

        // 大厅规则：关掉一切伤害与自然生成，避免在大厅里出意外
        HubSuiteConfig.ServerBehaviour behaviour = new HubSuiteConfig.ServerBehaviour();
        behaviour.invulnerable = true;
        behaviour.pvp = false;
        behaviour.fallDamage = false;
        behaviour.mobGriefing = false;
        behaviour.tntIgnition = false;
        behaviour.explosionBlockDamage = false;
        behaviour.fireSpread = false;
        behaviour.allowFlight = true;
        behaviour.keepHungerFull = true;
        behaviour.allowItemDrop = false;

        java.util.Map<String, String> rulesConfig = new java.util.LinkedHashMap<>();
        rulesConfig.put("SPAWN_MOBS", "false");
        rulesConfig.put("SPAWN_MONSTERS", "false");
        rulesConfig.put("ADVANCE_WEATHER", "false");
        rulesConfig.put("KEEP_INVENTORY", "true");
        rulesConfig.put("IMMEDIATE_RESPAWN", "true");
        rulesConfig.put("SHOW_DEATH_MESSAGES", "false");
        rulesConfig.put("MOB_DROPS", "false");
        rulesConfig.put("ENTITY_DROPS", "false");

        ServerRules rules = new ServerRules(ID, behaviour);
        rules.initialize(rulesConfig);

        HubSuiteConfig.WorldKind kind = config.voidGenerator
                ? HubSuiteConfig.WorldKind.VOID
                : HubSuiteConfig.WorldKind.NORMAL;
        LevelStem stem = WorldFactory.createStem(
                server,
                WorldFactory.normalizeDimensionType(config.dimensionType, "minecraft:the_end"),
                kind,
                List.of());

        ResourceKey<Level> dimension = WorldBuilder.dimensionKey(ID);
        ServerLevel level = WorldBuilder.create(server, save, dimension, stem, config.seed, true);

        PlayableWorld.SpawnPoint spawn = new PlayableWorld.SpawnPoint(
                config.spawnX, config.spawnY, config.spawnZ, config.spawnYaw, config.spawnPitch);
        try {
            level.setRespawnData(LevelData.RespawnData.of(
                    dimension,
                    BlockPos.containing(spawn.x(), spawn.y(), spawn.z()),
                    spawn.yaw(),
                    spawn.pitch()));
        } catch (Exception e) {
            HubSuite.logger().warn("设置大厅出生点失败：{}", e.toString());
        }

        save.register(level);
        save.bindPlayerStorage(dimension);
        RulesManager.register(dimension, rules);

        // 纯虚空世界没有地面，必须自己铺平台，否则玩家一进来就掉下去
        //
        // 注意**必须看返回值**：没加载时 SpawnPlatform 会返回 CHUNKS_NOT_READY
        // （它不再去同步生成区块），这时只打日志是没用的 —— 平台就永远不铺了。
        // 实测现象：新存档进大厅直接掉虚空、脚下什么都没有。
        // 所以要登记进"待铺"列表，由 WorldsManager 的每 tick 重试补上。
        if (config.buildPlatform) {
            int blocks = SpawnPlatform.build(level, spawn, config.platformRadius);
            if (blocks == SpawnPlatform.CHUNKS_NOT_READY) {
                SubServer.queueSpawnPlatform(level, spawn, config.platformRadius, "lobby");
                HubSuite.logger().info("大厅出生平台等区块加载后补铺");
            } else {
                HubSuite.logger().info("大厅出生平台已生成：{} 个方块", blocks);
            }
        }

        // 大厅继承主世界的昼夜与天气，必须压成"永昼 + 晴朗"
        LobbyEnvironment.makeEternalDay(level);

        return new Lobby(server, config, save, rules, level, spawn);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "\u00A7b大厅";
    }

    @Override
    public ServerLevel level() {
        return level;
    }

    @Override
    public ServerRules rules() {
        return rules;
    }

    @Override
    public IsolatedSave save() {
        return save;
    }

    @Override
    public PlayableWorld.SpawnPoint spawn() {
        return spawn;
    }

    @Override
    public String description() {
        return "&7选择要进入的服务器";
    }

    public HubSuiteConfig.LobbyConfig config() {
        return config;
    }

    public List<ServerLevel> levels() {
        return List.of(level);
    }

    public void save(boolean flush) {
        save.save(levels(), flush);
    }

    public void close() {
        RulesManager.unregister(level.dimension());
        ServerLevelsAccess.remove(server, level.dimension());
        save.close();
    }
}
