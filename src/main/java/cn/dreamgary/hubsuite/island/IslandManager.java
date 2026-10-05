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
import net.minecraft.world.level.block.LeavesBlock;
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
        int y = oceanAnchorY();
        List<BlockPos> taken = new ArrayList<>();
        for (Island island : islands.values()) {
            taken.add(anchorOf(island));
        }

        long spacingSq = (long) spacing * spacing;
        int step = Math.max(48, spacing / 3);

        /*
         * 岛要放在**海洋**上，不能随便找块地就放。这里有两个坑：
         *
         *  1. 用 getBlockState 探测会**同步生成整个区块** —— 第一版扫了几百个点，
         *     直接把服务器卡到看门狗强杀（单 tick 60 秒）。所以只查噪声群系。
         *  2. 单点噪声群系不够准：某些坐标判成海洋，实际地表却是森林
         *     （实测把岛建到了 old_growth_birch_forest 上）。所以候选点要求
         *     **3x3 采样全是海洋**才采纳。
         *
         * 另外：绝不回落到原点 —— 那里几乎一定是陆地，正是坑 2 的来源。
         */
        var oceanBiomes = java.util.Set.of("ocean", "deep_ocean", "warm_ocean",
                "lukewarm_ocean", "cold_ocean", "deep_lukewarm_ocean",
                "deep_cold_ocean", "deep_warm_ocean");

        int attempts = 0;
        for (int ring = 1; ring <= 24 && attempts < 400; ring++) {
            int r = ring * step;
            for (int i = 0; i < 12 && attempts < 400; i++) {
                double angle = (Math.PI * 2 / 12) * i + ring * 0.37;
                int x = (int) Math.round(Math.cos(angle) * r);
                int z = (int) Math.round(Math.sin(angle) * r);
                attempts++;

                if (!isOceanPatch(x, z, oceanBiomes)) {
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
                    HubSuite.logger().info("为海岛选定位置 ({}, {}, {})（尝试 {} 次）",
                            x, y, z, attempts);
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
        return new BlockPos(fx, y, fz);
    }

    /**
     * 这个坐标周围是不是**成片的**海洋（3x3 噪声采样全是海洋群系）。
     *
     * <p>单点采样不够准：噪声在群系边界会抖动，某些点判成海洋、
     * 实际地表却是森林。
     */
    private boolean isOceanPatch(int x, int z, java.util.Set<String> oceanBiomes) {
        int span = 48;
        for (int dx = -span; dx <= span; dx += span) {
            for (int dz = -span; dz <= span; dz += span) {
                if (!isNoiseOcean(x + dx, z + dz, oceanBiomes)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 用噪声群系判断（不生成区块，开销极小）。 */
    private boolean isNoiseOcean(int x, int z, java.util.Set<String> oceanBiomes) {
        try {
            int quartY = Math.max(0, (oceanAnchorY() - 1) >> 2);
            var key = level.getNoiseBiome(x >> 2, quartY, z >> 2).unwrapKey().orElse(null);
            return key != null && oceanBiomes.contains(key.identifier().getPath());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 海岛锚点的高度：跟着海平面走，让岛刚好露出水面。 */
    private int oceanAnchorY() {
        if (config.oceanIslandY > 0) {
            return config.oceanIslandY;
        }
        try {
            int sea = level.getSeaLevel();
            // 岛面定在海平面 +1：下面几层填到水里，上面留出干燥的沙地
            return sea + 1;
        } catch (Throwable t) {
            return 64;
        }
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
        HubSuite.logger().info("为 {} 分配岛屿：方格({}, {}) 岛型 {}",
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
     * <p><b>必须覆盖到方形的四个角。</b>地形是按 {@code dx,dz ∈ [-r, r]} 铺的
     * **正方形**，而 {@link #islandAt} 用的是**圆形**判定；直接用 r 的话
     * 角落方块（距中心 {@code r*sqrt(2)}）落在圈外 ——
     * 后果有两重：岛主在自己岛的角落不能建造，而**别人可以拆/建那里的方块**。
     * 默认 plotSize=256（r=4）时，9×9 表层里有 12 格中招。
     *
     * <p>所以按外接圆半径判定（{@code ceil(r*sqrt(2))}）。
     */
    public int protectionRadius() {
        int r = islandRadius();
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
            int radius = islandRadius() + 4;

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
                    if (!level.getChunkSource().hasChunk(cx + dx, cz + dz)) {
                        HubSuite.logger().info(
                                "方格({}, {}) 的区块未加载，跳过地形清理（避免阻塞主线程）",
                                island.plotX, island.plotZ);
                        return;
                    }
                }
            }

            int cleared = 0;
            for (int dy = -8; dy <= 24; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        var pos = center.offset(dx, dy, dz);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                            cleared++;
                        }
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
     * 诊断：打印一座岛的当前实际方块状态与保护判定。
     * 用于排查"卡在树里""箱子位置不对""说不是我的区域"这类问题。
     */
    public void diagnose(Island island) {
        ServerLevel level = this.level;
        BlockPos center = anchorOf(island);
        // 注意：这里**不**主动加载区块 —— 诊断是可选操作，
        // 为了打印几行日志去同步生成海洋区块不值得（会被看门狗强杀）。
        // 区块没加载时读到的方块状态不可信，所以直接跳过。

        HubSuite.logger().info("=== 空岛诊断：方格({}, {}) 中心 {} ===", island.plotX, island.plotZ, center);
        for (int dy = 1; dy <= 3; dy++) {
            StringBuilder row = new StringBuilder();
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    var pos = center.offset(dx, dy, dz);
                    var b = level.getBlockState(pos);
                    if (!b.isAir()) {
                        row.append(String.format("%n    (%d,%d,%d) = %s", dx, dy, dz,
                                b.getBlock().getName().getString()));
                        if (dx == 2 && dz == -2 && dy == 1) {
                            row.append("   ← 应该是物资箱");
                        }
                        if (dx == -2 && dz == -2 && dy == 1) {
                            row.append("   ← 玩家落脚点");
                        }
                    }
                }
            }
            HubSuite.logger().info("  相对中心 dy={} 的非空气方块：{}", dy, row);
        }

        // 保护判定：检查箱子所在的四个角与中心
        int[][] probes = {{2, -2}, {3, -2}, {2, -3}, {3, -3}, {0, 0}, {-2, -2}, {4, 0}, {0, 4}};
        for (int[] pr : probes) {
            boolean allowed = canBuildFor(island, pr[0], pr[1]);
            HubSuite.logger().info("  保护判定 相对({}, {}): {}",
                    pr[0], pr[1], allowed ? "可建造" : "\u00A7c拒绝");
        }
    }

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
     * 穷举检查：以岛主身份逐格询问 canBuild，把拒绝的位置与原因列出来。
     *
     * <p>用来一次性定位"为什么我岛上某块地方不能建造"。
     */
    public void exhaustiveBuildCheck(ServerPlayer player, Island island) {
        BlockPos center = plotCenter(island.plotX, island.plotZ);
        HubSuite.logger().info("=== 穷举建造检查：{} @ 方格({},{}) 中心 {} ===",
                player.getName().getString(), island.plotX, island.plotZ, center);

        int deniedCount = 0;
        StringBuilder firstDenied = new StringBuilder();
        // 覆盖岛半径 12 格（远超地形半径 4），以及方格边界的四个角
        for (int dx = -12; dx <= 12; dx++) {
            for (int dz = -12; dz <= 12; dz++) {
                var pos = center.offset(dx, 1, dz);
                if (!canBuild(player, pos)) {
                    deniedCount++;
                    if (firstDenied.length() < 300) {
                        int[] pl = plotOf(pos.getX(), pos.getZ());
                        firstDenied.append(String.format("(%+d,%+d)[方格%d,%d] ", dx, dz, pl[0], pl[1]));
                    }
                }
            }
        }
        HubSuite.logger().info("  岛半径 12 格范围内：共 {} 格，被拒绝 {} 格", 25 * 25, deniedCount);
        if (deniedCount > 0) {
            HubSuite.logger().info("  被拒绝的位置：{}", firstDenied);
        }

        // 方格边界：岛主在自己的方格内应当全部允许
        int half = config.plotSize / 2;
        int[][] corners = {
                {-half + 1, -half + 1}, {half - 1, -half + 1},
                {-half + 1, half - 1}, {half - 1, half - 1},
                {-half - 1, 0}, {half + 1, 0}, {0, -half - 1}, {0, half + 1}};
        for (int[] c : corners) {
            var pos = center.offset(c[0], 1, c[1]);
            int[] pl = plotOf(pos.getX(), pos.getZ());
            boolean ok = canBuild(player, pos);
            boolean sameePlot = pl[0] == island.plotX && pl[1] == island.plotZ;
            HubSuite.logger().info("  边界点 相对({:+d},{:+d}) → 方格({},{})，判定={}，{}",
                    c[0], c[1], pl[0], pl[1],
                    ok ? "允许" : "\u00A7c拒绝",
                    sameePlot ? "(在自己方格内，应为允许)" : "(已跨到邻格，拒绝是对的)");
        }
    }

    /** 诊断用：按相对中心坐标判断保护，不依赖玩家对象。 */
    private boolean canBuildFor(Island island, int dx, int dz) {
        if (!config.protectPlots) {
            return true;
        }
        BlockPos center = plotCenter(island.plotX, island.plotZ);
        Optional<Island> at = islandAt(center.getX() + dx, center.getZ() + dz);
        return at.isPresent() && at.get().player.equals(island.player);
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
            // 老记录没有锚点字段：按放置方式补算，避免把岛铺到世界原点
            BlockPos computed = "ocean".equals(island.placement)
                    ? plotCenter(island.plotX, island.plotZ)
                    : plotCenter(island.plotX, island.plotZ);
            island.anchorX = computed.getX();
            island.anchorY = computed.getY();
            island.anchorZ = computed.getZ();
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
            boolean someoneThere = level.players().stream()
                    .anyMatch(p -> p.getUUID().toString().equals(uuid));
            if (!someoneThere) {
                continue;   // 玩家还没到，等下一轮
            }
            if (ensureTerrain(island)) {
                HubSuite.logger().info("方格({}, {}) 的地形已补铺完成", island.plotX, island.plotZ);
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
                if (!level.getChunkSource().hasChunk(cx0 + dx, cz0 + dz)) {
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

        // 1) 基础地形：按层配置铺一块方形小岛
        List<int[]> layers = parseLayers(type);
        for (int[] layer : layers) {
            if (System.currentTimeMillis() > deadline) {
                overBudget[0] = true;
                break;
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

        // 3c) 兜底：种树/放箱子之后再清一次落脚点，保证玩家一定不会被埋
        //
        //     树与箱子都在 +X/+Z 象限，落脚点在 −X/−Z，正常不会重叠；
        //     这一步是防护性的 —— 万一以后改了树形或加了装饰，
        //     也不会出现"出生卡在方块里"。
        clearAbove(level, spawnPos, 1, 5);

        // 4) 校验：中心方块必须已经被写成实体方块，否则说明生成失败
        var state = level.getBlockState(center);
        if (state.isAir()) {
            HubSuite.logger().error("建岛失败：方格({}, {}) 的中心 {} 仍是空气！"
                            + "（层配置 {} 条，方块 id 解析可能失败）",
                    island.plotX, island.plotZ, center, layers.size());
        } else {
            // 打印中心柱的方块构成，便于确认"树干有没有出问题"
            StringBuilder column = new StringBuilder();
            for (int dy = -3; dy <= 6; dy++) {
                var b = level.getBlockState(center.offset(0, dy, 0));
                column.append(dy == 0 ? "[" : " ").append(center.getY() + dy).append(':')
                        .append(b.isAir() ? "空" : b.getBlock().getName().getString())
                        .append(dy == 0 ? "]" : "");
            }
            HubSuite.logger().info("已生成岛屿：方格({}, {}) 类型 {}，中心 {} = {}，半径 {}，层 {} 条",
                    island.plotX, island.plotZ, island.type, center,
                    state.getBlock().getName().getString(), radius, layers.size());
            HubSuite.logger().info("  中心柱：{}", column);
            var treeBase = center.offset(TREE_OFFSET_X, 1, TREE_OFFSET_Z);
            var chestAt = center.offset(CHEST_OFFSET_X, 1, CHEST_OFFSET_Z);
            HubSuite.logger().info("  布局：岛中心({}, {}, {}) / 树({}, {}, {}) / 箱子({}, {}, {}) / 落脚点({}, {}, {})",
                    center.getX(), center.getY(), center.getZ(),
                    treeBase.getX(), treeBase.getY(), treeBase.getZ(),
                    chestAt.getX(), chestAt.getY(), chestAt.getZ(),
                    spawnPos.getX(), spawnPos.getY(), spawnPos.getZ());
            StringBuilder spawnColumn = new StringBuilder();
            for (int dy = 0; dy <= 3; dy++) {
                var b = level.getBlockState(spawnPos.offset(0, dy, 0));
                spawnColumn.append(dy == 0 ? "[" : " ").append(spawnPos.getY() + dy).append(':')
                        .append(b.isAir() ? "空" : b.getBlock().getName().getString())
                        .append(dy == 0 ? "]" : "");
            }
            HubSuite.logger().info("  落脚点柱：{}", spawnColumn);
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
        island.terrainPainted = true;
        return true;
    }

    /** 把某个位置上方若干格清成空气（半径 r 的方形范围）。 */
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

    private void placeTree(ServerLevel level, BlockPos base) {
        int trunk = 4;
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, true);
        for (int i = 0; i < trunk; i++) {
            level.setBlockAndUpdate(base.offset(0, i, 0), log);
        }
        BlockPos top = base.offset(0, trunk, 0);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2 && dy == 1) {
                        continue;
                    }
                    BlockPos pos = top.offset(dx, dy, dz);
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlockAndUpdate(pos, leaves);
                    }
                }
            }
        }
        level.setBlockAndUpdate(top.above(), leaves);
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
