package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.List;

/**
 * 海岛维度的**结构加密**。
 *
 * <p><b>为什么不直接改结构密度：</b>结构出现的频率写在原版的
 * {@code structure_set} 数据里（spacing / separation），而那是**全局注册表**——
 * 用数据包覆盖会同时影响生存服和创造服，把整包的平衡性一起改掉。
 *
 * <p>所以这里换个思路：**玩家最可能探索的地方是"自己岛附近"**，
 * 那就只在那里定向补几个海洋结构（沉船 / 海底废墟）。
 * 效果是"出海就能看到东西"，而其它维度一点不受影响。
 *
 * <p>放置过程完全复用原版的结构生成 API（{@code Structure#generate} +
 * {@code StructureStart#placeInChunk}），所以产出与自然生成的结构一模一样，
 * 战利品表、方块实体、朝向都是原版的。
 */
public final class OceanStructures {

    /** 会被定向补放的海洋结构（按优先级）。 */
    private static final List<String> CANDIDATES = List.of(
            "shipwreck", "ocean_ruin_cold", "ocean_ruin_warm", "shipwreck_beached");

    /** 距离岛屿多远找位置（格）。太近会和岛重叠，太远玩家看不到。 */
    private static final int MIN_DISTANCE = 48;
    private static final int MAX_DISTANCE = 128;

    /** 每次尝试的候选角度数。 */
    private static final int ATTEMPTS = 8;

    private OceanStructures() {
    }

    /**
     * 在某座岛附近补一个海洋结构。
     *
     * <p>调用时机很重要：必须在**区块已经加载**之后（结构放置会写方块）。
     * 建岛流程本来就在区块就绪后才跑，所以挂在 {@code IslandManager} 的
     * 地形铺好之后即可。
     *
     * @param anchor           岛的锚点
     * @param randomSeed       用于选角度与结构种类
     * @param occupiedChunks   已经放过结构的区块，避免重复堆在一起
     * @return 放下的结构名；没放下返回 null
     */
    public static String placeNear(ServerLevel level, BlockPos anchor, long randomSeed,
                                   java.util.Set<Long> occupiedChunks) {
        try {
            var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
            var generator = level.getChunkSource().getGenerator();
            var randomState = level.getChunkSource().randomState();
            var templateManager = level.getStructureManager();

            var random = new net.minecraft.util.RandomSource() {
                // 用固定种子的简单线性同余，避免引入新的随机源依赖
                private long state = randomSeed == 0 ? 88172645463325252L : randomSeed;

                private long next() {
                    state ^= state << 13;
                    state ^= state >>> 7;
                    state ^= state << 17;
                    return state;
                }

                @Override
                public net.minecraft.util.RandomSource fork() {
                    return this;
                }

                @Override
                public net.minecraft.world.level.levelgen.PositionalRandomFactory forkPositional() {
                    return null;
                }

                @Override
                public void setSeed(long seed) {
                    this.state = seed;
                }

                @Override
                public int nextInt() {
                    return (int) next();
                }

                @Override
                public int nextInt(int bound) {
                    return Math.floorMod((int) next(), bound);
                }

                @Override
                public long nextLong() {
                    return next();
                }

                @Override
                public boolean nextBoolean() {
                    return (next() & 1) != 0;
                }

                @Override
                public float nextFloat() {
                    return (next() >>> 8) / (float) (1 << 24);
                }

                @Override
                public double nextDouble() {
                    return (next() >>> 11) / (double) (1L << 53);
                }

                @Override
                public double nextGaussian() {
                    return nextDouble() * 2.0 - 1.0;   // 近似即可，只用来选角度
                }
            };

            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2;
                int distance = MIN_DISTANCE
                        + random.nextInt(MAX_DISTANCE - MIN_DISTANCE);
                int x = anchor.getX() + (int) Math.round(Math.cos(angle) * distance);
                int z = anchor.getZ() + (int) Math.round(Math.sin(angle) * distance);

                ChunkPos chunkPos = new ChunkPos(x >> 4, z >> 4);
                if (occupiedChunks != null && !occupiedChunks.add(chunkPos.pack())) {
                    continue;   // 这个区块已经放过
                }
                // 区块没加载就别放：结构放置会写方块，未加载会同步生成整片区块
                if (!level.getChunkSource().hasChunk(chunkPos.x(), chunkPos.z())) {
                    continue;
                }

                String want = CANDIDATES.get(random.nextInt(CANDIDATES.size()));
                Identifier id = Identifier.withDefaultNamespace(want);
                Holder<Structure> holder = registry.get(
                        ResourceKey.create(Registries.STRUCTURE, id)).orElse(null);
                if (holder == null) {
                    continue;
                }

                StructureStart start = holder.value().generate(
                        holder,
                        level.dimension(),
                        level.registryAccess(),
                        generator,
                        generator.getBiomeSource(),
                        randomState,
                        templateManager,
                        level.getSeed(),
                        chunkPos,
                        0,
                        level,
                        biome -> true);
                if (!start.isValid()) {
                    continue;
                }

                // 结构可能跨越多个区块，逐块放置
                var box = start.getBoundingBox();
                for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                    for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                        ChunkPos cp = new ChunkPos(cx, cz);
                        if (!level.getChunkSource().hasChunk(cx, cz)) {
                            continue;
                        }
                        start.placeInChunk(level, level.structureManager(), generator,
                                level.getRandom(), box, cp);
                    }
                }
                HubSuite.logger().info("海岛附近补放结构：{} @ ({}, {})（距岛 {} 格）",
                        want, x, anchor.getY(), z, distance);
                return want;
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("补放海洋结构失败（不影响岛屿生成）：{}", t.toString());
        }
        return null;
    }

    /**
     * 判断某个位置是不是"开阔水域"，避免把沉船放到岛上。
     *
     * <p>只用噪声群系判断，不读方块 —— 读方块在未加载区块上会同步生成整片区块。
     */
    public static boolean looksLikeOcean(ServerLevel level, int x, int z) {
        try {
            int quartY = QuartPos.fromBlock(level.getSeaLevel());
            var key = level.getNoiseBiome(QuartPos.fromBlock(x), quartY, QuartPos.fromBlock(z))
                    .unwrapKey().orElse(null);
            return key != null && key.identifier().getPath().contains("ocean");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 让 IDE 知道 Biome 这个泛型参数被用到了（Predicate 的类型参数）。 */
    @SuppressWarnings("unused")
    private static boolean anyBiome(Holder<Biome> biome) {
        return true;
    }
}
