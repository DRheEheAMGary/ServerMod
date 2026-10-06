package cn.dreamgary.hubsuite.island;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.SubServer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 空岛管理：方格分配、岛屿生成、归属保护。
 *
 * <p>一个虚空维度里用网格划分出许多"方格"（plot），每座岛占一个方格，
 * 方格之间留间隔。这样一座世界就能容纳大量玩家，比"每人一个维度"省得多。
 *
 * <p>归属关系保存在 {@code config/hubsuite/skyblock/islands.json}，
 * 与存档解耦，方便备份和迁移。
 */
public final class IslandManager {

    /** 树相对岛中心的偏移。必须非零，见 generate() 里的说明。 */
    private static final int TREE_OFFSET_X = 2;
    private static final int TREE_OFFSET_Z = 0;

    /** 物资箱相对岛中心的偏移（与树错开）。 */
    private static final int CHEST_OFFSET_X = 2;
    private static final int CHEST_OFFSET_Z = 2;

    /**
     * 落脚点相对岛屿中心方块的高度偏移。
     *
     * <p>岛屿中心方块（草方块）在 {@code +0}（世界坐标 Y=100），
     * 玩家脚底落在 {@code +1}，也就是配置里的 {@code spawnY = 101}。
     *
     * <p>注意：中心那一格在 Minecraft 里占据的是 Y=100~101 这段，
     * 所以脚踩在 Y=101 时，{@code blockPosition()} 会报中心方块那一格
     * （看起来像"站在方块里"）。这是方块坐标与实体坐标的换算惯例，
     * 视觉上玩家是正常站在岛面上的。
     */
    private static final int SPAWN_OFFSET_Y = 1;

    /** 一座岛的归属记录。 */
    public static final class Island {
        public String player;
        public String name = "";
        /** 方格坐标（不是方块坐标）。 */
        public int plotX;
        public int plotZ;

        /**
         * 岛的实际锚点（世界坐标）。
         *
         * <p>经典空岛是**网格**放置的，锚点由 {@code plotX/plotZ} 算出来；
         * 海岛是**海里按距离摆**的，没有网格，锚点必须显式记下来。
         */
        public int anchorX;
        public int anchorY;
        public int anchorZ;

        /** 放置方式：{@code grid} = 网格（经典空岛）；{@code ocean} = 海里按距离摆。 */
        public String placement = "grid";

        /**
         * 地形是否已经铺好。
         *
         * <p>海岛的建岛是**延迟**的：先只登记归属与锚点，地形等玩家真到岛上
         * 再铺 —— 见 {@link #ensureTerrain} 里的说明。
         */
        public boolean terrainPainted = false;

        public String type = "classic";
        public long createdAt;
        /** 邀请进来的玩家名字（小写）。 */
        public List<String> members = new ArrayList<>();

        public Island() {
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final TypeToken<Map<String, Island>> MAP_TYPE = new TypeToken<>() {
    };

    private final SubServer server;
    private final IslandConfig config;
    private final Path storeFile;
    private final Map<String, Island> islands = new LinkedHashMap<>();

    /**
     * 地形还没铺好的岛（等区块加载）。
     *
     * <p>为什么需要：建岛发生在"玩家还在旧维度"的时候，目标区块必然未加载，
     * {@link #generate} 会安全地早退并把这个岛登记进来。
     * 之后必须有人重试 —— 否则玩家被传送到一座空岛上方，直接掉进海里/虚空，
     * 而岛**永远不会生成**（实测踩过）。
     */
    private final java.util.Set<String> pendingTerrain =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 这个管理器管的是哪个维度入口（例如 {@code classic} / {@code ocean}）。
     *
     * <p>空岛服有多个岛屿维度，每个维度一个管理器、一份归属文件 ——
     * 否则两种岛型的归属会互相覆盖。
     */
    private final String entryId;

    /** 这个维度实际用的服务端世界。 */
    private final ServerLevel level;

    public IslandManager(SubServer skyblockServer, SubServer.Entry entry, IslandConfig config) {
        this.server = skyblockServer;
        this.entryId = entry.id();
        this.level = entry.level();
        this.config = config;
        this.storeFile = FabricLoader.getInstance().getConfigDir()
                .resolve(HubSuite.MOD_ID).resolve("skyblock")
                .resolve("islands-" + entry.id() + ".json");
        load();
    }

    /** 这个管理器对应的维度入口 id。 */
    public String entryId() {
        return entryId;
    }

    /** 这个维度实际用的世界。 */
    public ServerLevel level() {
        return level;
    }

    // ------------------------------------------------------------------
    // 方格坐标 ↔ 方块坐标
    // ------------------------------------------------------------------

    /**
     * 一座岛的实际锚点（方块坐标）。
     *
     * <p>网格放置的岛由方格坐标算出；海里按距离摆的岛用它自己记的坐标。
     */
    public BlockPos anchorOf(Island island) {
        if ("ocean".equals(island.placement)) {
            return new BlockPos(island.anchorX, island.anchorY, island.anchorZ);
        }
        return plotCenter(island.plotX, island.plotZ);
    }

    /** 这个管理器是不是"海里按距离摆"的模式。 */
    public boolean isOceanPlacement() {
        return "ocean".equals(entryId);
    }

    /**
     * 在海里给新岛找一个空位。
     *
     * <p>螺旋向外找，要求与**所有已有岛**的距离不小于 {@code oceanSpacing}。
     * 这样不需要网格也能保证岛不重叠，而且间距可以按需调整。
     */
    private BlockPos findFreeOceanSpot() {
        int spacing = Math.max(64, config.oceanSpacing);
        List<BlockPos> taken = new ArrayList<>();
        for (Island island : islands.values()) {
            taken.add(anchorOf(island));
        }

        long spacingSq = (long) spacing * spacing;
        int step = Math.max(48, spacing / 3);

        /*
         * 岛要放在**海洋**上，不能随便找块地就放。这里有两个坑：
         *
         *  1. **绝不读方块。** 用 getBlockState 探测会同步生成整个区块
         *     （第一版扫了几百个点，直接把服务器卡到看门狗强杀，单 tick 60 秒）。
         *     所以只看噪声群系 —— getNoiseBiome 不碰区块，开销极小。
         *  2. 单点噪声群系不够准：某些坐标判成海洋，实际地表却是别的地形。
         *     所以候选点要求 **3x3 采样全是海洋**才采纳。
         *
         * 另外：绝不回落到原点 —— 那里未必是海，正是坑 2 的来源。
         */
        int attempts = 0;
        for (int ring = 1; ring <= 24 && attempts < 400; ring++) {
            int r = ring * step;
            for (int i = 0; i < 12 && attempts < 400; i++) {
                double angle = (Math.PI * 2 / 12) * i + ring * 0.37;
                int x = (int) Math.round(Math.cos(angle) * r);
                int z = (int) Math.round(Math.sin(angle) * r);
                attempts++;

                if (!isOceanPatch(x, z)) {
                    continue;
                }
                boolean ok = true;
                for (BlockPos pos : taken) {
                    long dx = pos.getX() - x;
                    long dz = pos.getZ() - z;
                    if (dx * dx + dz * dz < spacingSq) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    int y = oceanAnchorY();
                    HubSuite.logger().debug("为海岛选定位置 ({}, {}, {})（尝试 {} 次，群系 {}，"
                                    + "距已有岛最近 {} 格）",
                            x, y, z, attempts, biomeNameAt(x, z),
                            taken.isEmpty() ? "无（这是第一座）" : nearestIslandDistance(x, z, taken));
                    return new BlockPos(x, y, z);
                }
            }
        }

        int fallbackIndex = taken.size() + 1;
        int fx = fallbackIndex * spacing;
        int fz = -fallbackIndex * spacing;
        HubSuite.logger().warn(
                "海里没找到成片海洋（尝试 {} 次，已有 {} 座岛），岛放在 ({}, {}) —— 可能不在海洋上，请检查世界生成",
                attempts, taken.size(), fx, fz);
        return new BlockPos(fx, oceanAnchorY(), fz);
    }

    /** 离已有岛最近的距离（只用于日志）。 */
    private long nearestIslandDistance(int x, int z, List<BlockPos> taken) {
        long best = -1;
        for (BlockPos pos : taken) {
            long dx = pos.getX() - x;
            long dz = pos.getZ() - z;
            long d = (long) Math.sqrt((double) (dx * dx + dz * dz));
            if (best < 0 || d < best) {
                best = d;
            }
        }
        return best;
    }

    /** 某个坐标的群系名（只用于日志，失败返回 "?"）。 */
    private String biomeNameAt(int x, int z) {
        try {
            return level.getNoiseBiome(x >> 2, quartYFor(), z >> 2)
                    .unwrapKey().map(k -> k.identifier().toString()).orElse("?");
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 取群系用的 quart Y：贴着水面往下一点，避开"水面之上"的空气柱。 */
    private int quartYFor() {
        return Math.max(0, (oceanAnchorY() - 1) >> 2);
    }

    /**
     * 这个坐标周围是不是**成片的**海洋（3x3 噪声采样全是海洋群系）。
     *
     * <p>单点采样不够准：噪声在群系边界会抖动，某些点判成海洋、
     * 实际地表却是别的。采样间距取 48 格，正好覆盖一座岛加上水下缓坡的范围。
     */
    /**
     * 区块是否已经到 **FULL** —— 只有 FULL 才能安全读/写方块而不阻塞主线程。
     *
     * <p><b>别用 {@code hasChunk} 当这个判据：</b>它对"已加载到任意非空阶段"
     * 都返回 true，而 {@code getBlockState} / {@code setBlockAndUpdate} 要的是 FULL，
     * 没到就 {@code getChunk(...).join()} 同步等下去 —— 建岛/铺平台跑在
     * 每 tick 的重试路径上，这一下就是 60 秒看门狗强杀（实测两次，
     * 崩溃栈分别落在 SpawnPlatform.build 与岛屿地形清理上）。
     */
    private static boolean isChunkFullyLoaded(ServerLevel level, int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz) != null;
    }

    private boolean isOceanPatch(int x, int z) {
        int span = 48;
        for (int dx = -span; dx <= span; dx += span) {
            for (int dz = -span; dz <= span; dz += span) {
                if (!isOceanNoise(x + dx, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 用**噪声群系**判断某个坐标是不是海洋。
     *
     * <p>只看噪声，**不读方块、不加载区块** —— 选位会扫几百个点，
     * 任何一个 getBlockState 都会把整片区块同步生成出来（实测被看门狗强杀过）。
     *
     * <p>群系名单只有一份，来自生成器本身（见
     * {@link cn.dreamgary.hubsuite.world.OceanWorldGenerator#oceanBiomeIds()}）。
     */
    private boolean isOceanNoise(int x, int z) {
        try {
            var key = level.getNoiseBiome(x >> 2, quartYFor(), z >> 2).unwrapKey().orElse(null);
            return key != null
                    && cn.dreamgary.hubsuite.world.OceanWorldGenerator
                            .isOceanBiome(key.identifier().getPath());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 供自检使用：选位用的"纯噪声"海洋判定。
     *
     * <p>自检会拿一个**没加载**的坐标调它，然后断言那个区块仍然没加载 ——
     * 这是"选位绝不生成区块"的回归防线。
     */
    public boolean oceanBiomeAt(int x, int z) {
        return isOceanNoise(x, z);
    }

    /**
     * 海岛锚点的高度。
     *
     * <p>就是**水面高度 + {@link #OCEAN_ISLAND_ABOVE_WATER}**，
     * 由生成器给出（{@code waterSurface()}），而不是现扫方块量出来的。
     *
     * <p>踩过的坑：这里原来是 {@code findWaterSurface(level, x, z)} —— 读方块。
     * 而选位阶段那个位置的区块**多半还没加载**，读一格就等于同步生成整片区块：
     * 一次选位最多 13 个候选 × 9 个采样点 = 一百多次重地形生成挤在同一个 tick 里。
     * 而且 {@link #buildOceanIsland} 铺地形时又会**重新量一次**水面，
     * 两个算法一旦不一致，就会出现"地形铺在一层、出生点在另一层"。
     *
     * <p>海面高度在这个维度是**全局常量**：含水层已关闭、海底恒在海平面以下，
     * 所以水方块顶面处处都是 {@code waterSurface()}。铺地形时不再重新量，
     * 直接用记录里的锚点高度 —— 真源只有一个。
     */
    private int oceanAnchorY() {
        if (config.oceanIslandY > 0) {
            return config.oceanIslandY;
        }
        return cn.dreamgary.hubsuite.world.OceanWorldGenerator.waterSurface()
                + OCEAN_ISLAND_ABOVE_WATER;
    }

    /** 方格中心对应的方块坐标。 */
    public BlockPos plotCenter(int plotX, int plotZ) {
        int cell = config.cellSize();
        int x = plotX * cell + config.islandOffsetX;
        int z = plotZ * cell + config.islandOffsetZ;
        return new BlockPos(x, config.islandY, z);
    }

    /**
     * 某个方块坐标属于哪个方格。
     *
     * <p><b>注意这里减去了 cell/2</b>：方格是以"格子中心"定义的
     * （方格 (0,0) 的中心在世界原点），所以它的覆盖范围是
     * {@code [-cell/2, +cell/2)}，而不是 {@code [0, cell)}。
     *
     * <p>不减这个偏移会导致 **方格边界正好落在格子中心上** ——
     * 于是中心在原点的岛会被边界从中间劈开，左半边被判成邻格，
     * 玩家在自己岛上有一半地方不能建造（实测踩过：
     * "箱子半边提示不是我的空岛区域"）。
     */
    public int[] plotOf(double x, double z) {
        int cell = config.cellSize();
        double half = cell / 2.0;
        return new int[]{
                (int) Math.floor((x + half) / cell),
                (int) Math.floor((z + half) / cell)};
    }

    /** 岛屿生成点（玩家落脚处）：岛中心上方一格。 */
    /**
     * 岛屿的玩家落脚点。
     *
     * <p>刻意**不放在岛屿正中心** —— 中心附近可能有树、箱子或装饰。
     * 树与箱子统一放在 +X/+Z 象限，玩家落脚点放在 −X/−Z 象限，
     * 这样即使树长得再高、叶子铺得再开，也不会把玩家埋住
     * （实测出现过"出生卡在树干里""掉虚空回岛也卡在树里"）。
     */
    public Vec3 spawnOf(Island island) {
        BlockPos center = anchorOf(island);
        // +2 而不是 +1：中心那一格（center）是岛面，center+1 是"贴着地面"的位置，
        // 玩家落下去时脚会插进方块。抬高到 center+2 才是站在地表上的正确高度。
        return new Vec3(center.getX() + 0.5, center.getY() + SPAWN_OFFSET_Y, center.getZ() + 0.5);
    }

    /** 出生点对应的方块坐标（清空与校验用）。 */
    public BlockPos spawnBlockOf(Island island) {
        return anchorOf(island).offset(0, SPAWN_OFFSET_Y, 0);
    }

    // ------------------------------------------------------------------
    // 分配
    // ------------------------------------------------------------------

    /** 取（或创建）某玩家的岛。 */
    /**
     * 取玩家在**本维度岛型**上的岛；没有就建一座。
     *
     * <p>岛型由这个管理器绑定的维度决定（见构造器），
     * 传进来的 {@code typeId} 只用于校验 —— 空岛服的两个岛屿维度
     * 各有一个管理器，不该互相建对方的岛。
     */
    public Island getOrCreate(UUID uuid, String playerName, String typeId) {
        Island existing = islands.get(uuid.toString());
        if (existing != null && existing.type.equals(entryId)) {
            return existing;
        }
        if (existing != null) {
            // 记录被另一种岛型占用了：上层应当按维度分文件，不该走到这里
            HubSuite.logger().warn(
                    "玩家 {} 的岛屿记录是 {} 岛型，但当前维度是 {} —— 已按当前维度重建记录",
                    playerName, existing.type, entryId);
            islands.remove(uuid.toString());
        }

        Island created = new Island();
        created.player = uuid.toString();
        created.name = playerName;
        created.type = entryId;
        created.createdAt = System.currentTimeMillis();

        if (isOceanPlacement()) {
            // 海岛：海里按距离摆，没有网格
            created.placement = "ocean";
            BlockPos spot = findFreeOceanSpot();
            created.anchorX = spot.getX();
            created.anchorY = spot.getY();
            created.anchorZ = spot.getZ();
            // 方格号留作展示用的序号（同一套编号规则，方便 /island info 读）
            created.plotX = spot.getX() / Math.max(1, config.cellSize());
            created.plotZ = spot.getZ() / Math.max(1, config.cellSize());
            // 地形延迟到玩家真正上岛时再铺：海里现场生成区块很重，
            // 在这里同步做会把主线程卡到看门狗强杀（实测）。
            created.terrainPainted = false;
        } else {
            int[] free = findFreePlot();
            created.plotX = free[0];
            created.plotZ = free[1];
            BlockPos center = plotCenter(created.plotX, created.plotZ);
            created.anchorX = center.getX();
            created.anchorY = center.getY();
            created.anchorZ = center.getZ();
        }

        if (!"ocean".equals(created.placement)) {
            // 网格模式是虚空世界，铺地形很便宜，直接尝试做掉。
            // 注意要用返回值：区块没加载时 generate 会跳过并返回 false，
            // 这时**不能**标记成已铺（否则以后永远不会补铺，岛会一直是空气）。
            created.terrainPainted = generate(created);
        }
        islands.put(uuid.toString(), created);
        save();
        HubSuite.logger().debug("为 {} 分配岛屿：方格({}, {}) 岛型 {}",
                playerName, created.plotX, created.plotZ, created.type);
        return created;
    }

    /** 螺旋向外找第一个没被占用的方格。 */
    private int[] findFreePlot() {
        java.util.Set<Long> taken = new java.util.HashSet<>();
        for (Island island : islands.values()) {
            taken.add(key(island.plotX, island.plotZ));
        }
        if (!taken.contains(key(0, 0))) {
            return new int[]{0, 0};
        }
        for (int ring = 1; ring < 512; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.abs(dx) != ring && Math.abs(dz) != ring) {
                        continue;
                    }
                    if (!taken.contains(key(dx, dz))) {
                        return new int[]{dx, dz};
                    }
                }
            }
        }
        return new int[]{0, 0};
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** 取玩家在**本维度**的岛。 */
    public Optional<Island> islandOf(UUID uuid) {
        Island island = islands.get(uuid.toString());
        if (island == null || !island.type.equals(entryId)) {
            return Optional.empty();
        }
        return Optional.of(island);
    }

    /** 取玩家在**指定岛型**上的岛（跨维度查询用）。 */
    public Optional<Island> islandOfType(UUID uuid, String typeId) {
        Island island = islands.get(uuid.toString());
        if (island == null || !island.type.equals(typeId)) {
            return Optional.empty();
        }
        return Optional.of(island);
    }

    /** 这个圈子里所有岛（不分岛型）。 */
    public java.util.Collection<Island> allIslands() {
        return java.util.Collections.unmodifiableCollection(islands.values());
    }

    /**
     * 某个坐标归属于哪座岛。
     *
     * <p>网格模式按方格判定；海洋模式**按距离**判定（岛是海里点上去的，
     * 没有方格概念）。
     */
    public Optional<Island> islandAt(double x, double z) {
        if (isOceanPlacement()) {
            int radius = protectionRadius();
            Island best = null;
            double bestSq = Double.MAX_VALUE;
            for (Island island : islands.values()) {
                BlockPos a = anchorOf(island);
                double dx = a.getX() + 0.5 - x;
                double dz = a.getZ() + 0.5 - z;
                double distSq = dx * dx + dz * dz;
                if (distSq <= (double) radius * radius && distSq < bestSq) {
                    best = island;
                    bestSq = distSq;
                }
            }
            return Optional.ofNullable(best);
        }
        int[] plot = plotOf(x, z);
        return islands.values().stream()
                .filter(i -> i.plotX == plot[0] && i.plotZ == plot[1])
                .findFirst();
    }

    /** 岛的地形半径（和 generate 里用的是同一个算法）。 */
    public int islandRadius() {
        return Math.max(2, Math.min(6, config.plotSize / 64));
    }

    /**
     * 保护判定用的半径。
     *
     * <p>两种放置方式的地形形状不同，保护圈也必须跟着不同 ——
     * 保护圈比地形小，会出现"岛主在自己岛上不能建造、别人却能拆"的怪事。
     *
     * <p>经典空岛：地形是按 {@code dx,dz ∈ [-r, r]} 铺的**正方形**，
     * 而 {@link #islandAt} 用的是**圆形**判定；直接用 r 的话
     * 角落方块（距中心 {@code r*sqrt(2)}）落在圈外 ——
     * 默认 plotSize=256（r=4）时，9×9 表层里有 12 格中招。
     * 所以按外接圆半径判定（{@code ceil(r*sqrt(2))}）。
     *
     * <p>海岛：地形是**不规则圆形海岸线 + 水下缓坡**
     * （见 {@link #buildOceanIsland}），最远伸到
     * {@code r + 海岸线抖动(≤1.8) + 缓坡圈数}。按这个算，再加 1 格余量。
     */
    public int protectionRadius() {
        int r = islandRadius();
        if (isOceanPlacement()) {
            return (int) Math.ceil(r + OCEAN_SHORE_WOBBLE_MAX + OCEAN_SKIRT_RINGS) + 1;
        }
        return (int) Math.ceil(r * 1.4142135623730951) + 1;   // r * √2
    }

    /**
     * 某个坐标是不是"岛与岛之间的公共区域"。
     *
     * <p>网格模式下，一个方格内的空地都算岛主的；海洋模式下只有岛本身算，
     * 海面是公共区域（谁都不能圈海）。
     */
    public boolean isWilderness(double x, double z) {
        if (isOceanPlacement()) {
            return islandAt(x, z).isEmpty();
        }
        int[] plot = plotOf(x, z);
        return islands.values().stream()
                .noneMatch(i -> i.plotX == plot[0] && i.plotZ == plot[1]);
    }

    public int count() {
        return islands.size();
    }

    /**
     * 删除一座岛：清掉归属，并把地形也清干净。
     *
     * <p><b>为什么必须清地形：</b>只清归属的话，方块会留在世界里。
     * 之后同一个方格被重新分配给别的玩家时，新旧两座岛就会**叠在一起**
     * （实测出现过"两个空岛卡一起了"）。清理范围就是这座岛的方格中心。
     */
    public boolean delete(UUID uuid) {
        Island removed = islands.remove(uuid.toString());
        if (removed == null) {
            return false;
        }
        clearTerrain(removed);
        save();
        return true;
    }

    /** 把一座岛的地形清成空气（保留 y=0 以下不动）。 */
    private void clearTerrain(Island island) {
        try {
            ServerLevel level = this.level;
            BlockPos center = anchorOf(island);
            // 清理范围必须**盖住整座岛**（含不规则海岸线与水下缓坡），
            // 否则重新分配时会和上一座岛的残留叠在一起。
            int radius = protectionRadius() + 1;

            /*
             * 区块没加载就直接放弃清理。
             *
             * getBlockState / setBlockAndUpdate 在未加载区块上都会走
             * getChunk(..., FULL, true) —— 主线程原地等整片区块生成
             * （海洋地形极重，实测被看门狗强杀过）。hasChunk 只查已加载的
             * ChunkHolder，不会触发生成。
             *
             * 代价是"没加载的部分清不掉"，但那些区块的地形本来也会在
             * 下次进入时被 generate 覆盖，比搞崩服务器划算。
             */
            int cx = center.getX() >> 4;
            int cz = center.getZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!isChunkFullyLoaded(level, cx + dx, cz + dz)) {
                        HubSuite.logger().info(
                                "方格({}, {}) 的区块未加载，跳过地形清理（避免阻塞主线程）",
                                island.plotX, island.plotZ);
                        return;
                    }
                }
            }

            /*
             * 清理的范围与"清成什么"都要**按放置方式分开算**：
             *
             *   · 经典空岛在虚空里，只有中心那点方块，清 ±8 格、清成空气就行；
             *   · 海岛是"从海底长出来"的，石头一直填到海床（可能往下 30+ 格），
             *     只清 -8 会在水下留一根石桩；
             *   · 而且海岛维度**整片都是水**，岛占掉的那块水在重建时不会自己回来 ——
             *     清成空气等于在海里挖一根 25×25 的空气柱，四周的水立刻灌进去、
             *     海面塌陷（实测挖出过从 Y=62 直通 Y=12 的大坑）。
             *
             * 所以海岛：纵向一直扫到最深可能的海床，**水面以下还原成水**、
             * 水面以上清成空气。这样海面高度不变、也不会出现空腔。
             * （代价是岛正下方会留下一段比原来深的水；原始海床高度没有存过，
             *   做不到逐格还原 —— 重建时填海床又会把它填回去。）
             */
            boolean ocean = isOceanPlacement();
            int fromDy = ocean ? -(MAX_SEABED_SEARCH + 2) : -8;
            int toDy = 24;
            int waterY = cn.dreamgary.hubsuite.world.OceanWorldGenerator.waterSurface();
            BlockState water = Blocks.WATER.defaultBlockState();

            int cleared = 0;
            for (int dy = fromDy; dy <= toDy; dy++) {
                int y = center.getY() + dy;
                // 水面以下恢复成水，以上清成空气
                BlockState target = ocean && y <= waterY
                        ? water : Blocks.AIR.defaultBlockState();
                int flags = ocean && dy < -3
                        ? net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                        : net.minecraft.world.level.block.Block.UPDATE_ALL;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        var pos = center.offset(dx, dy, dz);
                        BlockState state = level.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        if (target.is(Blocks.WATER)
                                && !state.getFluidState().isEmpty()) {
                            continue;   // 已经是水，别重复写
                        }
                        if (!target.is(Blocks.WATER) && state.is(Blocks.AIR)) {
                            continue;
                        }
                        level.setBlock(pos, target, flags);
                        cleared++;
                    }
                }
            }
            HubSuite.logger().info("已清理方格({}, {}) 的岛屿地形：{} 个方块",
                    island.plotX, island.plotZ, cleared);
        } catch (Throwable t) {
            HubSuite.logger().warn("清理岛屿地形失败：{}", t.toString());
        }
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    /**
     * 以某个玩家的身份扫描他岛上"可建造"的实际范围。
     *
     * <p>直接回答"为什么岛上的某些方块说不是我的区域" ——
     * 把每个相对坐标的判定结果列出来，一看就知道边界在哪、和预期差多少。
     */
    public void auditBuildArea(ServerPlayer player, Island island) {
        BlockPos center = plotCenter(island.plotX, island.plotZ);
        HubSuite.logger().info("=== 建造权限审计：{} 的岛（方格 {},{}，中心 {}）===",
                player.getName().getString(), island.plotX, island.plotZ, center);

        int radius = Math.max(2, Math.min(6, config.plotSize / 64));
        StringBuilder allowed = new StringBuilder();
        StringBuilder denied = new StringBuilder();
        for (int dx = -radius - 1; dx <= radius + 1; dx++) {
            for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                var pos = center.offset(dx, 1, dz);
                boolean ok = canBuild(player, pos);
                String cell = String.format("(%d,%d)", dx, dz);
                if (ok) {
                    allowed.append(cell).append(' ');
                } else {
                    denied.append(cell).append(' ');
                }
            }
        }
        HubSuite.logger().info("  可建造：{}", allowed);
        HubSuite.logger().info("  被拒绝：{}", denied);

        // 关键点单独确认
        int[][] key = {{0, 0}, {2, -2}, {2, -1}, {3, -2}, {1, -2}, {-2, -2}, {-1, -2}, {-2, -1}};
        for (int[] k : key) {
            boolean ok = canBuild(player, center.offset(k[0], 1, k[1]));
            HubSuite.logger().info("    关键点 ({},{}) 相对中心 → {} （世界坐标 {},{},{}）",
                    k[0], k[1], ok ? "可建造" : "\u00A7c拒绝",
                    center.getX() + k[0], center.getY() + 1, center.getZ() + k[1]);
        }
    }

    /**
     * 建岛的时间预算（毫秒）。
     *
     * <p>为什么需要：在海里建岛要先让那片区块生成出来，而海洋地形的生成很重
     * （噪声 + 含水层 + 结构）。我第一版没有限制，直接把服务器卡到看门狗强杀
     * （"A single server tick took 60.00 seconds"）。
     *
     * <p>超预算就停下并告警：岛的地形会等区块自然加载后再补齐，
     * 总比整个服务器崩掉好。
     */
    private static final long GENERATE_BUDGET_MS = 4000;

    /**
     * 确保这座岛的地形已经铺好；没铺就现在补。
     *
     * <p>为什么海岛要延迟铺：海里现场生成区块极重（噪声 + 含水层 + 结构），
     * 在"玩家刚发出 /island ocean"那一 tick 同步做完，会把主线程卡到
     * 看门狗强杀（实测："A single server tick took 60.00 seconds"）。
     *
     * <p>延迟到这里就安全了：玩家马上要传送到岛上，**这些区块本来就必须加载**，
     * 加载成本无法避免；而且此时是传送流程的一部分，不再叠加在别的操作上。
     */
    public boolean ensureTerrain(Island island) {
        if (island.terrainPainted) {
            return true;
        }
        pendingTerrain.add(island.player);
        if (island.anchorY == 0) {
            /*
             * 老记录没有锚点字段（配置升级前的存档）。
             *
             * 网格模式可以从方格号反推；海岛模式**不能** ——
             * 海里点上去的岛没有方格，只存了一个展示用的序号，
             * 反推会把岛铺到世界原点去。它的 X/Z 本来就是对的，
             * 只补一个高度就够了。
             */
            if ("ocean".equals(island.placement)) {
                island.anchorY = oceanAnchorY();
                HubSuite.logger().warn("海岛记录缺少高度，已按水面补成 Y={}（X/Z 沿用记录值 {}, {}）",
                        island.anchorY, island.anchorX, island.anchorZ);
            } else {
                BlockPos computed = plotCenter(island.plotX, island.plotZ);
                island.anchorX = computed.getX();
                island.anchorY = computed.getY();
                island.anchorZ = computed.getZ();
            }
        }
        boolean painted = generate(island);
        if (painted) {
            pendingTerrain.remove(island.player);
            save();
        }
        return painted;
    }

    /**
     * 每个 tick 重试补铺未完成的地形（由 IslandService 注册到 tick 事件）。
     *
     * <p>只处理**玩家已经在岛上**的那些岛：这时区块本来就必须加载，
     * 补铺不会额外制造阻塞；玩家没到的岛继续等，不浪费主线程。
     */
    public void tickPendingTerrain() {
        if (pendingTerrain.isEmpty()) {
            return;
        }
        for (String uuid : new java.util.ArrayList<>(pendingTerrain)) {
            Island island = islands.get(uuid);
            if (island == null) {
                pendingTerrain.remove(uuid);
                continue;
            }
            /*
             * 不再要求"玩家已经在岛上"。
             *
             * 一开始的条件是 level.players() 里必须有这个玩家 —— 但区块加载的
             * 原因很多（别的玩家路过、票据、结构生成），玩家没到不代表区块没加载。
             * 而 generate() 开头就有 hasChunk 守卫，没加载会**立刻返回**，
             * 每 tick 对每座待铺岛做 9 次 hasChunk 查询的成本可以忽略。
             *
             * 放宽之后，岛只要区块就绪就会被铺好，不再依赖"玩家恰好站在那里"。
             */
            if (ensureTerrain(island)) {
                HubSuite.logger().debug("方格({}, {}) 的地形已补铺完成", island.plotX, island.plotZ);
            }
        }
    }

    /** 关服时清空待办（避免持有旧引用）。 */
    public void clearPending() {
        pendingTerrain.clear();
    }

    /**
     * 清空并重新生成一座岛。
     *
     * @return true 表示地形真的铺下去了；false 表示区块没加载、本次跳过
     *         （调用方可以稍后再试，见 {@link #ensureTerrain}）
     */
    public boolean generate(Island island) {
        ServerLevel level = this.level;
        BlockPos center = anchorOf(island);
        long deadline = System.currentTimeMillis() + GENERATE_BUDGET_MS;
        boolean[] overBudget = {false};

        /*
         * 大前提：**区块必须已经加载**，否则直接放弃本次生成。
         *
         * 为什么：level.setBlockAndUpdate() 内部会走 getChunkAt()，
         * 也就是"往未加载区块写方块 = 强制同步生成整片区块"。
         * 海洋/正常地形的区块生成很重，实测把主线程卡到看门狗强杀
         * （A single server tick took 60.00 seconds，踩了三次）。
         *
         * 所以这里先检查区块在不在；不在就什么都不做，让 IslandService
         * 在玩家真正要落地时再调一次 ensureTerrain()——那一刻区块本来就
         * 必须加载，成本无法避免，也不会叠加在别的操作上。
         */
        int cx0 = center.getX() >> 4;
        int cz0 = center.getZ() >> 4;
        boolean chunksReady = true;
        for (int dx = -1; dx <= 1 && chunksReady; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!isChunkFullyLoaded(level, cx0 + dx, cz0 + dz)) {
                    chunksReady = false;
                    break;
                }
            }
        }
        if (!chunksReady) {
            HubSuite.logger().info(
                    "方格({}, {}) 的区块还没加载，地形稍后补铺（避免阻塞主线程）",
                    island.plotX, island.plotZ);
            island.terrainPainted = false;
            return false;
        }
        IslandConfig.IslandType type = config.type(island.type);

        int radius = Math.max(2, Math.min(6, config.plotSize / 64));
        int baseY = center.getY();

        // 先把目标区块加载出来，否则 setBlockAndUpdate 会静默无效（实测踩过）
        int placed = 0;
        /*
         * 关键：**不要**在这里调用 level.getChunk()。
         *
         * 它会同步把整片区块生成出来。海洋地形很重（噪声 + 含水层 + 结构），
         * 一次 3x3 就能把主线程卡几十秒 —— 实测被看门狗以
         * "A single server tick took 60.00 seconds" 强杀。
         *
         * 正确做法：先用**票据**告诉引擎"这片区块我等着用"，
         * 让它异步生成；本次先把地形写进去（未加载的区块会安全地忽略写入），
         * 等区块真正就绪后再补一次。这样主线程不会被阻塞。
         */
        try {
            int cx = center.getX() >> 4;
            int cz = center.getZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.getChunkSource().addTicketWithRadius(
                            net.minecraft.server.level.TicketType.PLAYER_SPAWN,
                            new net.minecraft.world.level.ChunkPos(cx + dx, cz + dz), 0);
                }
            }
        } catch (Throwable t) {
            HubSuite.logger().debug("建岛前申请区块票据失败：{}", t.toString());
        }

        // 1) 基础地形。
        //
        //    海岛走**另一条路径**：它的顶面要对齐海平面、底下要一直填到天然海床
        //    （海里凭空浮着一块 4 格厚的石板很怪）。经典空岛是虚空世界，
        //    照层配置铺就行。
        List<int[]> layers = parseLayers(type);
        if (isOceanPlacement()) {
            if (buildOceanIsland(level, center, radius, type, layers, deadline, overBudget)) {
                HubSuite.logger().debug("海岛地形已铺好（中心 {}）", center);
            }
        } else {
            paintLayers(level, center, baseY, radius, layers, deadline, overBudget);
        }


        // 2) 清空"玩家落脚点"周围的悬空空间（必须在种树之前，否则会把树干挖掉）
        BlockPos spawnPos = center.offset(0, SPAWN_OFFSET_Y, 0);
        clearAbove(level, spawnPos, 1, 3);

        // 3) 树：种在偏心位置，避免占用出生点所在的列
        // 树放在岛屿中心**旁边**，而不是正中心。
        //
        // 关键原因：岛屿正中心同时也是子服的出生点（plotCenter(0,0) == (0,100,0)）。
        // 如果树长在正中心，任何"没找到岛、退回子服出生点"的路径都会把玩家
        // 直接塞进树干里（实测踩过：玩家坐标正好是 0 100 0，就是树干）。
        // 把树挪开一格，两条路径都落在空地上，容错。
        if (type.tree) {
            placeTree(level, center.offset(TREE_OFFSET_X, 1, TREE_OFFSET_Z));
        }

        // 3b) 箱子与初始物资（放在种树之后）
        //
        //     位置：树的斜对角。原来放在 (radius-2, 1, radius-2)，
        //     而树也在同一格 —— 结果箱子被树干"卡掉"（实测）。
        //     现在两者固定错开，并且放之前先清空那一格。
        if (type.chest) {
            BlockPos chestPos = center.offset(CHEST_OFFSET_X, 1, CHEST_OFFSET_Z);
            // 先清空（含上方），避免被树叶/树干占用
            for (int dy = 0; dy <= 1; dy++) {
                level.setBlockAndUpdate(chestPos.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
                List<ItemStack> loot = parseLoot(type);
                for (int i = 0; i < loot.size() && i < chest.getContainerSize(); i++) {
                    chest.setItem(i, loot.get(i));
                }
                HubSuite.logger().debug("方格({}, {}) 的物资箱放在 ({}, {}, {})，共 {} 种物资",
                        island.plotX, island.plotZ,
                        chestPos.getX(), chestPos.getY(), chestPos.getZ(), loot.size());
            } else {
                HubSuite.logger().warn("方格({}, {}) 的物资箱方块实体创建失败（位置 {}）",
                        island.plotX, island.plotZ, chestPos);
            }
        }

        if (overBudget[0]) {
            HubSuite.logger().warn(
                    "建岛超出时间预算（{} ms），地形只铺了一部分；区块加载后会自然补齐。方格({}, {})",
                    GENERATE_BUDGET_MS, island.plotX, island.plotZ);
        }

        /*
         * 3c) 落脚点兜底：**只清落脚点那一根柱子**，绝不扩到 3×3。
         *
         * 踩过的坑（用户反馈"树接近一半树叶消失，中心 3×3 变成空气"）：
         * 这里原来是 clearAbove(spawnPos, 1, 5) —— 一个以落脚点为中心、
         * 半径 1、高 5 的**立方体**。而树在 center+(2,0)、树冠半径 2，
         * 覆盖 center.x+0 .. center.x+4，与那个 3×3 区域**必然重叠**，
         * 于是树冠被挖掉一大块。
         *
         * 而且它跑在种树**之后**（925 行那次在种树之前，是对的），
         * 所以挖掉的正是已经种好的树叶。
         *
         * 玩家真正需要空出来的只有**脚下和头顶**这两格
         * （落脚点 y 与 y+1），所以这里清 1×1×2。
         *
         * 为什么高度必须压到 2：树冠最低那层在 y+3、再上一层在 y+4
         * （树干 3 格 + 树冠），清到 +3 就会把那两格树叶打掉 ——
         * 自检实测"树冠缺了 2/46 格树叶，位置 (-2,0,0)(-2,1,0)"。
         */
        clearAbove(level, spawnPos, 0, 1);

        // 4) 校验：中心方块必须已经被写成实体方块，否则说明生成失败
        var state = level.getBlockState(center);
        if (state.isAir()) {
            HubSuite.logger().error("建岛失败：方格({}, {}) 的中心 {} 仍是空气！"
                            + "（层配置 {} 条，方块 id 解析可能失败）",
                    island.plotX, island.plotZ, center, layers.size());
        } else {
            HubSuite.logger().debug("已生成岛屿：方格({}, {}) 类型 {}，中心 {} = {}，半径 {}，层 {} 条",
                    island.plotX, island.plotZ, island.type, center,
                    state.getBlock().getName().getString(), radius, layers.size());
        }

        if (overBudget[0]) {
            // 超时中断了 → 绝不能标记成"已铺"。
            // 标记了的话 ensureTerrain 之后永远早退，"以后再补"永远不会发生，
            // 玩家会永久得到一座半成品岛（甚至表层没铺、人直接掉下去）。
            HubSuite.logger().warn(
                    "方格({}, {}) 地形未铺完（超出 {} ms 预算），已登记待补铺",
                    island.plotX, island.plotZ, GENERATE_BUDGET_MS);
            pendingTerrain.add(island.player);
            return false;
        }
        // 地形铺好后，岛附近**不需要**手工补放海洋结构。
        //
        // 这里原来有一段"定向补放沉船/海底废墟"（OceanStructures）：当时的想法是
        // 改结构频率要覆盖全局注册表、会连带改掉生存/创造服，所以只在岛附近补几个。
        // 后来实测发现两件事，于是删掉了：
        //   1. 这个维度**所有**群系都是海洋群系，原版结构集本来就会密集刷沉船、
        //      海底废墟、海底神殿 —— /locate structure 实测出生点 66 格内就有沉船；
        //   2. 那段代码要求目标区块已加载（结构要写方块），而建岛时只有中心
        //      3×3 区块是加载的，48~128 格外**必然**没加载 → 8 次尝试全部跳过、
        //      一次都没成功过（日志里从来没有"补放结构"那行）。留着只会误导人。

        island.terrainPainted = true;
        return true;
    }

    /** 把某个位置上方若干格清成空气（半径 r 的方形范围）。 */
    /** 按层配置铺一块方形小岛（经典空岛用，虚空世界里没有天然地面可对齐）。 */
    private void paintLayers(ServerLevel level, BlockPos center, int baseY, int radius,
                             List<int[]> layers, long deadline, boolean[] overBudget) {
        for (int[] layer : layers) {
            if (System.currentTimeMillis() > deadline) {
                overBudget[0] = true;
                return;
            }
            int y = baseY + layer[0];
            BlockState state = blockState(layer[1]);
            if (state == null) {
                continue;
            }
            int shrink = layer[0] < -1 ? 1 : 0;   // 更深的层小一圈，看起来像岛
            for (int dx = -radius + shrink; dx <= radius - shrink; dx++) {
                for (int dz = -radius + shrink; dz <= radius - shrink; dz++) {
                    level.setBlockAndUpdate(center.offset(dx, y - baseY, dz), state);
                }
            }
        }
    }

    /**
     * 铺一座**海里的岛**。
     *
     * <p>从岛心往外分三层（全部由 {@link #islandRadius()} 和几个常量驱动）：
     * <pre>
     *   dist ≤ 半径            草地（层配置）—— **保证区**，树和箱子都在这里
     *   半径 &lt; dist ≤ 海岸线   沙滩环（海岸线 = 半径 + 抖动，所以这一圈宽窄不一）
     *   海岸线 &lt; dist ≤ +3圈   水下缓坡（每往外一圈低一格，铺沙滩）
     *   再往外                  天然海床，不动
     * </pre>
     *
     * <p><b>为什么不是正方形：</b>第一版就是"dx,dz 在 [-r,r] 全铺一遍"，
     * 结果海里出现一块 9×9 的**正方形草皮**，四壁是笔直的石头墙 ——
     * 从存档里导出方块一看就是个方块插在海里，完全不像岛（用户反馈
     * "看看海岛的生成逻辑"就是这个）。现在海岸线由三个频率的正弦叠加扰动，
     * 相位取自锚点坐标，所以同一座岛每次重建都长一样，不同岛各不相同。
     *
     * <p>顶面高度就是记录里的锚点高度 {@code center.getY()}
     * （= 水面，见 {@link #oceanAnchorY()}），这里**不再重新量水面** ——
     * 两个算法各算一次是"地形在一层、出生点在另一层"这类错位的根源。
     * 铺之前会实测一次水面做**一致性校验**，不一致只告警，不改高度。
     *
     * @return true 表示铺成功（可能因为超预算提前停下）
     */
    private boolean buildOceanIsland(ServerLevel level, BlockPos center, int radius,
                                     IslandConfig.IslandType type, List<int[]> layers,
                                     long deadline, boolean[] overBudget) {
        int topY = center.getY();

        /*
         * 一致性校验：锚点高度应当正好是水面（含水层关闭后水面是全局常量）。
         *
         * 实测有两类正常的不一致，都会打日志但不影响建岛：
         *   · 这一列水面之上有**冰**（frozen_ocean 的冰山/浮冰）—— 那几格不是流体，
         *     扫出来的"水面"会偏低；
         *   · 岛已经建过一次（重试补铺），中心柱现在是草/泥土，窗口里没有流体。
         * 所以只在**低于**水面时提示，并且措辞明确是"该处水面之上有东西"。
         */
        try {
            int measured = findWaterSurface(level, center.getX(), center.getZ());
            if (measured < topY) {
                HubSuite.logger().info(
                        "海岛中心柱上方比水面高 {} 格（多半是冰/浮冰或结构），"
                                + "已按水面 Y={} 铺岛并覆盖这些方块",
                        topY - measured, topY);
            }
        } catch (Throwable ignored) {
            // 校验失败不影响建岛
        }

        // 层配置里的 dy 是相对"地表基准"的，这里把基准挪到 topY。
        // 例如 0:grass_block → topY；-1:dirt → topY-1。
        List<int[]> sorted = new java.util.ArrayList<>(layers);
        sorted.sort(java.util.Comparator.comparingInt(a -> -a[0]));   // 从浅到深

        int deepestConfigured = 0;
        for (int[] layer : sorted) {
            deepestConfigured = Math.min(deepestConfigured, layer[0]);
        }

        BlockState filler = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        BlockState beach = beachState(type);
        boolean hasBeach = beach != null;

        /*
         * 海岸线：每条半径方向取一个"抖动值"。用锚点坐标当种子，
         * 保证同一座岛每次重建形状一致（自检与玩家看到的才会是同一个形状）。
         */
        var rng = net.minecraft.util.RandomSource.create(
                (long) center.getX() * 341873128712L + (long) center.getZ() * 132897987541L);
        double p1 = rng.nextDouble() * Math.PI * 2;
        double p2 = rng.nextDouble() * Math.PI * 2;
        double p3 = rng.nextDouble() * Math.PI * 2;

        int outer = radius + OCEAN_SKIRT_RINGS;
        int dry = 0;
        int shelf = 0;
        long started = System.currentTimeMillis();

        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                if (System.currentTimeMillis() > deadline) {
                    overBudget[0] = true;
                    return false;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                double angle = Math.atan2(dz, dx);

                /*
                 * 海岸线半径 = 半径 + 抖动。
                 *
                 * 抖动**只往外加、不往里减**（每一项都是 0.5+0.5*sin ≥ 0）：
                 * 半径以内的方块是**保证的草地**，树和箱子都放在那里
                 * （箱子在 2.83 格、树在 2.0 格）。如果抖动允许往里缩，
                 * 就有概率把箱子放到海岸线之外 —— 海里悬空一个箱子。
                 *
                 * 三个频率（3/5/7）叠出"大湾 + 中湾 + 小凹凸"三级轮廓，
                 * 幅度之和 = OCEAN_SHORE_WOBBLE_MAX。
                 */
                double wobble = 1.05 * (0.5 + 0.5 * Math.sin(3 * angle + p1))
                        + 0.70 * (0.5 + 0.5 * Math.sin(5 * angle + p2))
                        + 0.45 * (0.5 + 0.5 * Math.sin(7 * angle + p3));
                double shore = radius + wobble;

                if (dist <= radius) {
                    // ---- 岛心草地（保证区）----
                    placeLayers(level, x, z, topY, sorted, false, beach, filler, deepestConfigured);
                    dry++;
                } else if (dist <= shore) {
                    // ---- 沙滩环：形状的"不规则"主要来自这里 ----
                    placeLayers(level, x, z, topY, sorted, true, beach, filler, deepestConfigured);
                    dry++;
                } else if (hasBeach) {
                    // ---- 水下缓坡：每往外一圈低一格，表面铺沙滩 ----
                    //
                    // 这一圈的作用是**拆掉垂直石墙**：原本海岸线以外直接就是
                    // 几十格高的石头断崖，现在先有三圈往下退的浅滩，
                    // 从水面上看就是"沙滩一直延伸进海里"。
                    //
                    // 更缓的坡需要更宽的范围，而建岛只保证 3x3 区块已加载
                    // （中心点到边界最少 16 格），所以圈数不能无限加。
                    int ring = (int) Math.ceil(dist - shore);
                    if (ring > OCEAN_SKIRT_RINGS) {
                        continue;
                    }
                    int surfaceY = topY - ring;
                    var surface = new BlockPos(x, surfaceY, z);
                    if (isSolidGround(level.getBlockState(surface))) {
                        continue;   // 这里本来就是海床/浅滩，别抬高它
                    }
                    level.setBlock(surface, beach,
                            net.minecraft.world.level.block.Block.UPDATE_ALL);
                    level.setBlock(surface.below(), beach,
                            net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                    fillDown(level, x, surfaceY - 2, z, filler);
                    shelf++;
                }
            }
        }

        HubSuite.logger().info("海岛地形已铺好：中心 {}，草地半径 {} + 沙滩抖动 ≤{}，"
                        + "干地 {} 格 / 水下缓坡 {} 格，耗时 {} ms",
                center, radius, OCEAN_SHORE_WOBBLE_MAX, dry, shelf,
                System.currentTimeMillis() - started);
        return true;
    }

    /**
     * 铺一根柱子的"干地"部分：层配置 + 往下填到海床。
     *
     * @param sandy true 表示这一列在沙滩环上，最上面两层换成沙滩方块
     */
    private void placeLayers(ServerLevel level, int x, int z, int topY, List<int[]> sorted,
                             boolean sandy, BlockState beach, BlockState filler,
                             int deepestConfigured) {
        for (int[] layer : sorted) {
            BlockState state = sandy && beach != null && layer[0] >= -1
                    ? beach : blockState(layer[1]);
            if (state == null) {
                continue;
            }
            int flags = layer[0] >= -1
                    ? net.minecraft.world.level.block.Block.UPDATE_ALL
                    : net.minecraft.world.level.block.Block.UPDATE_CLIENTS;
            level.setBlock(new BlockPos(x, topY + layer[0], z), state, flags);
        }
        fillDown(level, x, topY + deepestConfigured - 1, z, filler);
    }

    /**
     * 从 {@code fromY} 往下填石头，直到碰到天然海床。
     *
     * <p>为什么要填到底：海里凭空浮着一块几格厚的石板非常出戏，
     * 而且玩家往下挖会直接漏进海里。填到海床之后，岛就是"从海底长出来的"。
     *
     * <p>用 {@code UPDATE_CLIENTS} 而不是 {@code setBlockAndUpdate}：
     * 深处几百上千格方块全部触发邻居更新与光照重算的话，
     * 会把建岛的时间预算撑爆（见 {@link #GENERATE_BUDGET_MS}）。
     */
    private void fillDown(ServerLevel level, int x, int fromY, int z, BlockState filler) {
        int floorY = findSeabed(level, x, fromY, z);
        for (int y = fromY; y > floorY; y--) {
            level.setBlock(new BlockPos(x, y, z), filler,
                    net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    /**
     * 岛型配置里的沙滩方块；没配就返回 null（= 不做沙滩，干地全用层配置）。
     *
     * <p>沙滩是**水平方向**的材质变化（同一层里岸边是沙、里面是草），
     * 层配置（{@code y偏移:方块}）表达不了，所以单独给一个字段。
     */
    private BlockState beachState(IslandConfig.IslandType type) {
        if (type == null || type.beach == null || type.beach.isBlank()
                || "none".equalsIgnoreCase(type.beach.trim())) {
            return null;
        }
        int id = parseBlockId(type.beach.trim());
        if (id < 0) {
            HubSuite.logger().warn("岛型 {} 的沙滩方块 '{}' 不存在，已忽略（干地用层配置的顶面方块）",
                    type.id, type.beach);
            return null;
        }
        return blockState(id);
    }

    /**
     * 找出某根柱子下方第一个天然实心方块的高度（= 海床）。
     *
     * <p>往下最多找 {@code MAX_SEABED_SEARCH} 格；找不到就返回世界底部，
     * 保证不会无限循环。
     *
     * <p>注意：调用前区块**必须已加载** —— {@code getBlockState} 在未加载的区块上
     * 会同步生成整片区块（海洋地形极重，会卡死主线程，实测被看门狗强杀过）。
     * {@code generate()} 开头已经做过 {@code hasChunk} 检查。
     */
    private int findSeabed(ServerLevel level, int x, int fromY, int z) {
        int limit = Math.max(level.getMinY() + 1, fromY - MAX_SEABED_SEARCH);
        for (int y = fromY; y > limit; y--) {
            if (isSolidGround(level.getBlockState(new BlockPos(x, y, z)))) {
                return y;
            }
        }
        return limit;
    }

    /**
     * 这一格算不算"实心地面"（海床）。
     *
     * <p><b>不能用 {@code isAir()} 判断</b> —— 水不是空气，
     * {@code !isAir()} 会把水当成海床，于是填充循环在第一格水就 break，
     * 岛变成一块**浮在水里的薄板**，底下全是水。
     * 实测症状：同一位置的两座岛锚点算出 Y=63 和 Y=55 两个值
     * （后者的"水面"其实是从薄板下面漏下去的水）。
     */
    private static boolean isSolidGround(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /**
     * 实测某根柱子上的**水面高度**（最上层水/流体方块所在的 Y）。
     *
     * <p>从海平面以上往下扫，找到第一格流体就是水面。
     * 找不到（例如那里已经是我们铺过的岛）就退回生成器的海平面。
     */
    private int findWaterSurface(ServerLevel level, int x, int z) {
        int sea = cn.dreamgary.hubsuite.world.OceanWorldGenerator.seaLevel();

        /*
         * 只在海平面上下一个**很窄**的窗口里找水面。
         *
         * 踩过的坑：一开始一路扫到世界底部，结果噪声世界里存在**地下含水层**，
         * 扫到 Y=-22 的水就当成"海面"，算出的岛顶高度是 Y=-21 ——
         * 岛直接被埋到地底（实测日志里出现"为海岛选定位置 (99, -21, 38)"）。
         *
         * 海洋的表面必然紧贴海平面，所以窗口取 ±8 格足够，
         * 找不到就退回海平面本身（说明这一列已经是陆地/被清过）。
         */
        int top = sea + 8;
        int bottom = sea - 8;
        for (int y = top; y >= bottom; y--) {
            var state = level.getBlockState(new BlockPos(x, y, z));
            if (state.getFluidState().isEmpty()) {
                continue;
            }
            // 找到最上面的一格水
            return y;
        }
        // 窗口里没有水（说明这一列已经是陆地，或者刚被清过）：
        // 退回**水方块的高度**而不是 seaLevel —— 后者会让岛顶再高出一格。
        return cn.dreamgary.hubsuite.world.OceanWorldGenerator.waterSurface();
    }

    /**
     * 海岛岛面相对**水面**的高度差（格）。
     *
     * <p>0 = 与水面齐平，1 = 高出水面一格。用户实测后要求"再往下一格"，
     * 所以现在是 0（岛面正好和最上层的水方块同高）。
     * 想微调就改这一个数 —— 锚点高度与地形铺设都用它，不会出现
     * "地形在一层、出生点在另一层"的错位。
     */
    private static final int OCEAN_ISLAND_ABOVE_WATER = 0;

    /**
     * 海岸线抖动的**幅度上限**（格）：干地最远伸到 {@code 半径 + 这个值}。
     *
     * <p>等于 {@link #buildOceanIsland} 里三个正弦的幅度之和（1.05 + 0.70 + 0.45）。
     * 两处必须一致 —— {@link #protectionRadius()} 用它算保护圈，
     * 算小了就会出现"岛主在自己岛的岸边不能建造"。
     */
    private static final double OCEAN_SHORE_WOBBLE_MAX = 2.20;

    /**
     * 水下缓坡的圈数：海岸线以外每往外一圈低一格，一直到这个圈数。
     *
     * <p>作用是让岛**从沙洲过渡进海里**，而不是一圈笔直的石头断崖。
     * 不能调太大：建岛只保证中心周围 3×3 区块已加载，
     * 中心点到那个范围至少有 16 格，圈数 + 半径超过它就会写到未加载的区块上
     * （那等于同步生成整片区块，本项目被看门狗强杀过好几次）。
     */
    private static final int OCEAN_SKIRT_RINGS = 3;

    /** 往下找海床的最大深度（防止在极深的海洋里铺太久）。 */
    private static final int MAX_SEABED_SEARCH = 48;

    private void clearAbove(ServerLevel level, BlockPos base, int radius, int height) {
        for (int dy = 0; dy <= height; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    var pos = base.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    /**
     * 在岛上种一棵**原版橡树**（走原版地物生成，不再手摆方块）。
     *
     * <p><b>为什么改成原版地物：</b>以前这里是手写的形状 —— 固定 3 格树干 +
     * 两层 5×5 去角 + 一层 3×3 + 顶盖。当时图的是"确定性"，但代价是
     * 长出来的树**和原版橡树不一样**（高度、树冠形状、树叶衰减都是假的），
     * 用户实测反馈："空岛的树还是沿用常规地物生成树吧"。
     *
     * <p>现在走 {@code TreeGrower.OAK.growTree(...)} —— 树苗长成大树用的就是它，
     * 所以形状、高度分布、树叶的 {@code persistent/距离} 全部与原版一致。
     *
     * <p><b>确定性：</b>随机源由**坐标**派生（不是每 tick 变的随机数），
     * 所以同一座岛每次重建都长成同一棵树。
     *
     * <p><b>安全前提：</b>整个生成区域（树干 base 往上约 8 格、水平 ±3 格）
     * 所在的区块必须已经到 FULL 才会往下走 —— 否则宁可不种，
     * 也绝不让地物生成去同步加载区块（那会阻塞主线程）。
     * 建岛本身就走"区块没就绪就登记待补铺"的延迟机制，下一次重试时会再种。
     */
    private void placeTree(ServerLevel level, BlockPos base) {
        // 树干下方必须是可长树的土壤：草方块 → 换成泥土（原版橡树要求 dirt 类）
        BlockPos belowPos = base.below();
        BlockState below = level.getBlockState(belowPos);
        if (below.is(Blocks.GRASS_BLOCK)) {
            level.setBlockAndUpdate(belowPos, Blocks.DIRT.defaultBlockState());
        }

        // 生成范围全部在已就位区块里才动手（树干 base 之上约 8 格、水平 ±3 格）
        if (!isTreeAreaReady(level, base)) {
            HubSuite.logger().info("岛上的树等区块就绪后再种：{}", base);
            return;
        }

        // 确定性随机源：同一个坐标每次得到同一棵树
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(
                base.getX() * 341873128712L + base.getZ() * 132897987541L + base.getY());
        BlockState saplingState = Blocks.OAK_SAPLING.defaultBlockState();
        boolean grown = net.minecraft.world.level.block.grower.TreeGrower.OAK.growTree(
                level, level.getChunkSource().getGenerator(), base, saplingState, random);
        if (grown) {
            HubSuite.logger().debug("种树完成（原版橡树地物）：{}", base);
        } else {
            HubSuite.logger().warn("原版橡树在 {} 没能长起来（上方空间可能不够），岛上将没有树", base);
        }
    }

    /** 树需要的空间（水平 ±3、向上 8 格）是否都在已就位区块里。 */
    private static boolean isTreeAreaReady(ServerLevel level, BlockPos base) {
        int minX = base.getX() - 3;
        int maxX = base.getX() + 3;
        int minZ = base.getZ() - 3;
        int maxZ = base.getZ() + 3;
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<int[]> parseLayers(IslandConfig.IslandType type) {
        List<int[]> result = new ArrayList<>();
        for (String raw : type.layers) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                int offset = Integer.parseInt(parts[0].trim());
                result.add(new int[]{offset, parseBlockId(parts[1].trim())});
            } catch (NumberFormatException e) {
                HubSuite.logger().warn("岛型 {} 的层配置非法：{}", type.id, raw);
            }
        }
        result.sort(Comparator.comparingInt(a -> a[0]));
        return result;
    }

    /** 把方块 id 编码成一个 int（高位是注册表 id），避免在配置里存对象。 */
    private int parseBlockId(String id) {
        var registry = level.getServer().registryAccess().lookupOrThrow(Registries.BLOCK);
        var key = ResourceKey.create(Registries.BLOCK, net.minecraft.resources.Identifier.parse(id));
        return registry.get(key).map(h -> registry.getId(h.value())).orElse(-1);
    }

    private BlockState blockState(int blockId) {
        if (blockId < 0) {
            return null;
        }
        var registry = level.getServer().registryAccess().lookupOrThrow(Registries.BLOCK);
        Block block = registry.byId(blockId);
        return block == null ? null : block.defaultBlockState();
    }

    private List<ItemStack> parseLoot(IslandConfig.IslandType type) {
        List<ItemStack> result = new ArrayList<>();
        var registry = level.getServer().registryAccess().lookupOrThrow(Registries.ITEM);
        for (String raw : type.loot) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw;
            int amount = 1;
            int star = raw.lastIndexOf('*');
            if (star > 0) {
                id = raw.substring(0, star).trim();
                try {
                    amount = Math.max(1, Integer.parseInt(raw.substring(star + 1).trim()));
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
            }
            var key = ResourceKey.create(Registries.ITEM, net.minecraft.resources.Identifier.parse(id));
            Item item = registry.get(key).map(h -> h.value()).orElse(null);
            if (item == null || item == Items.AIR) {
                HubSuite.logger().warn("岛型 {} 的物资 '{}' 不存在，已忽略。", type.id, id);
                continue;
            }
            result.add(new ItemStack(item, Math.min(amount, item.getDefaultMaxStackSize() * 4)));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 保护
    // ------------------------------------------------------------------

    /** 判断某玩家能否在某位置动方块。 */
    public boolean canBuild(ServerPlayer player, BlockPos pos) {
        if (!config.protectPlots) {
            return true;
        }
        if (player.isCreative()
                || player.permissions().hasPermission(
                        net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER)) {
            return true;
        }

        Optional<Island> mineIsland = islandOf(player.getUUID());
        boolean mine;

        if (isOceanPlacement()) {
            // 海岛：岛是海里点上去的，没有方格 —— 按"离我的岛够不够近"判定，
            // 而且海面（岛之外）是公共区域，谁都不能圈。
            mine = mineIsland.isPresent()
                    && islandAt(pos.getX(), pos.getZ())
                    .map(a -> a.player.equals(player.getUUID().toString()))
                    .orElse(false);
        } else {
            // 经典空岛：归属按"玩家自己的方格"判断，而不是按地形范围。
            //
            // 原因：岛的地形只有半径几格，但它所在的方格有几百格宽。
            // 如果按地形判断，玩家在自己方格内稍微往外走一点就会被拒绝建造 ——
            // 实测出现过"箱子半边提示不是我的空岛区域"。方格内的整片区域都属于岛主。
            int[] plot = plotOf(pos.getX(), pos.getZ());
            mine = mineIsland.isPresent()
                    && mineIsland.get().plotX == plot[0]
                    && mineIsland.get().plotZ == plot[1];
        }
        boolean member = false;
        if (!mine) {
            Optional<Island> at = islandAt(pos.getX(), pos.getZ());
            member = at.isPresent()
                    && at.get().members.contains(player.getName().getString().toLowerCase(java.util.Locale.ROOT));
        }

        boolean allowed = mine || member;
        if (!allowed) {
            // 降到 debug：正常游玩时不该刷屏，排查时开 debug 就能看到
            HubSuite.logger().debug(
                    "拒绝建造：{} 在 ({}, {}) → 归属={}，本人岛={}",
                    player.getName().getString(), pos.getX(), pos.getZ(),
                    islandAt(pos.getX(), pos.getZ())
                            .map(i -> i.name).orElse("公共区域/无主"),
                    mineIsland.map(i -> "方格 " + i.plotX + "," + i.plotZ).orElse("无岛"));
        }
        return allowed;
    }

    /** 加入成员（{@code /island invite} 用）。 */
    public boolean addMember(Island island, String playerName) {
        String key = playerName.toLowerCase(java.util.Locale.ROOT);
        if (island.members.contains(key)) {
            return false;
        }
        island.members.add(key);
        save();
        return true;
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    private void load() {
        try {
            if (!Files.exists(storeFile)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
                Map<String, Island> data = GSON.fromJson(reader, MAP_TYPE.getType());
                if (data != null) {
                    islands.putAll(data);
                }
            }
            HubSuite.logger().info("已载入空岛归属：{} 座。", islands.size());
        } catch (Exception e) {
            HubSuite.logger().error("载入空岛归属失败", e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling("islands.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(islands, writer);
            }
            try {
                Files.move(tmp, storeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException unsupported) {
                Files.move(tmp, storeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            HubSuite.logger().error("保存空岛归属失败", e);
        }
    }

    public Path storeFile() {
        return storeFile;
    }

    public IslandConfig config() {
        return config;
    }
}
