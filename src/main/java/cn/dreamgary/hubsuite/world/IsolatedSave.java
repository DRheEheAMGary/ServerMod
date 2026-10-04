package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.Lifecycle;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.WorldData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * 一个"独立存档"。这是本项目多世界方案的核心。
 *
 * <p>每个子服拥有：
 * <ul>
 *   <li>自己的 {@link LevelStorageSource.LevelStorageAccess}（目录 {@code <根目录>/<saveName>}）；</li>
 *   <li>自己的 {@code level.dat}（{@link PrimaryLevelData}，内含独立的
 *       gamerule / 难度 / 出生点 / 时间）；</li>
 *   <li>自己的玩家数据目录 {@code <save>/players}（背包、末影箱、经验、成就、统计都在这里）。</li>
 * </ul>
 *
 * <p>隔离为什么是"天然"的：玩家数据读取链路是
 * {@code PlayerList.loadPlayerData} → {@code worldData.overworldData()} →
 * {@code storageSource.createPlayerStorage()} → {@code storageSource.getLevelPath(PLAYER_DATA_DIR)}，
 * 其中 {@code storageSource} / {@code worldData} 都是"每存档一份"的对象。
 * 我们让每个子服的 {@code ServerLevel} 持有自己的这两个对象，
 * 于是切换子服时整条链路一起切换 —— 不需要影子存储，也不需要搬运物品。
 */
public final class IsolatedSave {

    private final MinecraftServer server;
    private final String saveName;
    private final LevelStorageSource.LevelStorageAccess access;
    private final WorldData worldData;
    private final Path root;
    private final boolean created;

    private IsolatedSave(MinecraftServer server,
                         String saveName,
                         LevelStorageSource.LevelStorageAccess access,
                         WorldData worldData,
                         boolean created) {
        this.server = server;
        this.saveName = saveName;
        this.access = access;
        this.worldData = worldData;
        this.root = access.getLevelDirectory().path();
        this.created = created;
    }

    /**
     * 打开（不存在则新建）一个独立存档。
     *
     * @param saveName   目录名，同时作为 levelId
     * @param name       世界显示名（写进 level.dat）
     * @param gameType   默认游戏模式
     * @param difficulty 难度
     */
    public static IsolatedSave open(MinecraftServer server,
                                    String saveName,
                                    String name,
                                    GameType gameType,
                                    Difficulty difficulty,
                                    boolean allowCommands) throws IOException {
        LevelStorageSource base = ServerInternals.storageSource(server).parent();
        LevelStorageSource.LevelStorageAccess access = base.createAccess(saveName);
        Path levelDat = access.getLevelPath(LevelResource.LEVEL_DATA_FILE);
        boolean fresh = !Files.exists(levelDat);

        WorldData worldData;
        if (fresh) {
            HubSuite.logger().info("新建独立存档 '{}'（{}）", saveName, name);
            worldData = createWorldData(server, name, gameType, difficulty, allowCommands);
            writeLevelData(access, worldData);
        } else {
            worldData = readWorldData(access);
            HubSuite.logger().info("载入已有独立存档 '{}'（{}）", saveName, name);
        }
        return new IsolatedSave(server, saveName, access, worldData, fresh);
    }

    private static WorldData createWorldData(MinecraftServer server,
                                             String name,
                                             GameType gameType,
                                             Difficulty difficulty,
                                             boolean allowCommands) {
        // 复用主世界的 data pack 配置，保证各子服看到的特性开关一致
        WorldDataConfiguration dataConfig = server.getWorldData().getDataConfiguration();
        LevelSettings settings = new LevelSettings(
                name,
                gameType,
                new LevelSettings.DifficultySettings(difficulty, false, false),
                allowCommands,
                dataConfig);
        return new PrimaryLevelData(
                settings,
                PrimaryLevelData.SpecialWorldProperty.NONE,
                Lifecycle.stable());
    }

    private static WorldData readWorldData(LevelStorageSource.LevelStorageAccess access) throws IOException {
        Path levelDat = access.getLevelPath(LevelResource.LEVEL_DATA_FILE);
        CompoundTag rootTag = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
        Tag dataTag = rootTag.getCompoundOrEmpty("Data");
        Dynamic<Tag> dynamic = new Dynamic<>(NbtOps.INSTANCE, dataTag);
        LevelSettings settings = LevelSettings.parse(dynamic, WorldDataConfiguration.DEFAULT);
        return PrimaryLevelData.parse(
                dynamic,
                settings,
                PrimaryLevelData.SpecialWorldProperty.NONE,
                Lifecycle.stable());
    }

    /** 写出 level.dat（先写临时文件再原子替换，避免异常退出留下半截文件）。 */
    public static void writeLevelData(LevelStorageSource.LevelStorageAccess access, WorldData data) throws IOException {
        CompoundTag tag = data.createTag(null);
        NbtUtils.addCurrentDataVersion(tag);
        CompoundTag root = new CompoundTag();
        root.put("Data", tag);

        Path target = access.getLevelPath(LevelResource.LEVEL_DATA_FILE);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("level.dat.hubsuite-tmp");
        NbtIo.writeCompressed(root, tmp);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public String saveName() {
        return saveName;
    }

    public Path root() {
        return root;
    }

    public boolean wasCreated() {
        return created;
    }

    public LevelStorageSource.LevelStorageAccess access() {
        return access;
    }

    public WorldData worldData() {
        return worldData;
    }

    /** 玩家数据文件的存放目录（26.1 的实际布局是 {@code <存档>/players/data/}）。 */
    public Path playersDir() {
        return access.getLevelPath(LevelResource.PLAYER_DATA_DIR);
    }

    /** 某个玩家的数据文件。名字就是 {@code <uuid>.dat}。 */
    public Path playerFile(UUID uuid) {
        return playersDir().resolve(uuid + ".dat");
    }

    public boolean hasPlayerFile(UUID uuid) {
        return Files.exists(playerFile(uuid));
    }

    /** 该子服当前已落盘的玩家数据文件数量（运维/自检用）。 */
    public long playerFileCount() {
        Path dir = playersDir();
        if (!Files.isDirectory(dir)) {
            return 0L;
        }
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".dat")).count();
        } catch (IOException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 把一个已加载的维度注册进服务端，从此它会被正常 tick / 保存 / 追踪玩家。 */
    public ServerLevel register(ServerLevel level) {
        ServerLevelsAccess.put(server, level);
        return level;
    }

    /**
     * 为本存档创建玩家数据存储，并登记到 {@link PlayerDataRouter}。
     *
     * <p>这一步是多世界隔离能成立的关键：原版玩家数据默认只写主世界存档，
     * 必须把它换成"按维度分发"，背包/经验/成就才会真正按子服分开。
     */
    public net.minecraft.world.level.storage.PlayerDataStorage bindPlayerStorage(
            net.minecraft.resources.ResourceKey<Level> dimension) {
        net.minecraft.world.level.storage.PlayerDataStorage storage =
                new net.minecraft.world.level.storage.PlayerDataStorage(access, server.getFixerUpper());
        PlayerDataRouter.register(this, storage, dimension);
        PlayerDataRouter.setDefaultFallback(storage);
        return storage;
    }

    /** 保存本存档的全部已加载维度与 level.dat。 */
    public void save(List<ServerLevel> levels, boolean flush) {
        for (ServerLevel level : levels) {
            try {
                level.save(null, flush, false);
            } catch (Exception e) {
                HubSuite.logger().error("保存子服 '{}' 的维度 {} 失败", saveName, level.dimension().identifier(), e);
            }
        }
        try {
            writeLevelData(access, worldData);
        } catch (IOException e) {
            HubSuite.logger().error("保存子服 '{}' 的 level.dat 失败", saveName, e);
        }
    }

    public void close() {
        try {
            access.close();
        } catch (IOException e) {
            HubSuite.logger().warn("关闭存档 '{}' 时出错", saveName, e);
        }
    }

    /** 拼一个 hubsuite 命名空间的维度 key。 */
    public static net.minecraft.resources.ResourceKey<Level> key(String path) {
        return net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("hubsuite", path));
    }
}
