package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.ArrayList;
import java.util.List;

/**
 * 海岛维度的**海洋世界生成器**。
 *
 * <p><b>为什么不能直接借用主世界的设置：</b>一开始的实现是
 * "拿 {@code NoiseGeneratorSettings.OVERWORLD} + OVERWORLD 群系预设"，
 * 但主世界的噪声路由器（continentalness）本来就会生成**大陆**，
 * 群系预设里也全是平原/森林这些陆地群系 —— 结果海岛服生成的是一个
 * 完完全全的常规世界，跟"海洋"没有关系（实测被用户抓到）。
 *
 * <p>所以这里自己组装两样东西：
 * <ol>
 *   <li><b>只有海洋群系的群系源</b> —— 按 温度 × 深度 铺开
 *       冻洋/冷水洋/温水洋/暖水洋 及其深海变体；</li>
 *   <li><b>保证是海的噪声路由器</b> —— 把主世界的 continentalness
 *       压缩到"远海"区间，并把 finalDensity 换成一个受控函数：
 *       海床恒定落在海平面以下，再用噪声让海底起伏。</li>
 * </ol>
 *
 * <p>群系选择与地形都由**同一个** {@code continents} 函数驱动，
 * 所以"深海处海床更低、浅海处海床更高"是自洽的，不会出现
 * 深海群系配浅水地形这种错位。
 */
public final class OceanWorldGenerator {

    /** 海平面（原版主世界也是 63）。 */
    private static final int SEA_LEVEL = 63;

    /**
     * 海床的平均深度（海平面往下多少格）。
     *
     * <p>配合下面的噪声振幅，海床会在 Y≈33~57 之间起伏，**始终低于海平面**
     * —— 这样整片世界都是海，不会冒出天然陆地。
     */
    private static final double BASE_DEPTH = 18.0;

    /** 海床起伏幅度（格）。 */
    private static final double DEPTH_VARIATION = 12.0;

    /** 洋流起伏的细节噪声幅度（格）。 */
    private static final double DETAIL_VARIATION = 4.0;

    private OceanWorldGenerator() {
    }

    /**
     * 造一个海洋维度的区块生成器。
     *
     * @return 区块生成器；构造失败时返回 null（调用方应回落到虚空）
     */
    public static net.minecraft.world.level.chunk.ChunkGenerator create(MinecraftServer server) {
        try {
            var access = server.registryAccess();
            var biomes = access.lookupOrThrow(Registries.BIOME);
            var noises = access.lookupOrThrow(Registries.NOISE);

            var vanilla = access.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();

            NoiseRouter router = oceanRouter(vanilla.noiseRouter(), noises);
            NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
                    vanilla.noiseSettings(),
                    vanilla.defaultBlock(),
                    vanilla.defaultFluid(),
                    router,
                    vanilla.surfaceRule(),
                    vanilla.spawnTarget(),
                    SEA_LEVEL,
                    vanilla.disableMobGeneration(),
                    vanilla.aquifersEnabled(),
                    vanilla.oreVeinsEnabled(),
                    vanilla.useLegacyRandomSource());

            var biomeSource = oceanBiomeSource(biomes);
            HubSuite.logger().info("海洋世界生成器已就绪：海洋群系 {} 种，海平面 Y={}，海床 Y≈{}~{}",
                    OCEAN_BIOMES.size(), SEA_LEVEL,
                    (int) (SEA_LEVEL - BASE_DEPTH - DEPTH_VARIATION - DETAIL_VARIATION),
                    (int) (SEA_LEVEL - BASE_DEPTH + DEPTH_VARIATION + DETAIL_VARIATION));
            return new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
                    biomeSource, Holder.direct(settings));
        } catch (Throwable t) {
            HubSuite.logger().error("构造海洋生成器失败，海岛维度将使用虚空世界", t);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 群系源
    // ------------------------------------------------------------------

    /**
     * 参与生成的海洋群系。
     *
     * <p>顺序即"温度带由冷到暖"，与 {@link #TEMPERATURE_BANDS} 一一对应。
     * 深海与浅海各取一个，所以每个温度带都有"靠近海面的浅海"和"深海"两档。
     */
    private static final List<String> OCEAN_BIOMES = List.of(
            "deep_frozen_ocean", "frozen_ocean",
            "deep_cold_ocean", "cold_ocean",
            "deep_lukewarm_ocean", "lukewarm_ocean",
            "deep_lukewarm_ocean", "warm_ocean");

    /** 温度带边界（与 {@link #OCEAN_BIOMES} 配对：每两格一个温度带）。 */
    private static final float[][] TEMPERATURE_BANDS = {
            {-1.00F, -0.45F},   // 冰冻
            {-0.45F, -0.15F},   // 寒冷
            {-0.15F, 0.20F},    // 温和
            {0.20F, 1.00F},     // 温暖
    };

    /** 深海 / 浅海的 continentalness 分界（与原版海洋判定一致）。 */
    private static final float DEEP_THRESHOLD = -0.455F;
    private static final float SHORE_THRESHOLD = -0.11F;

    /**
     * 只含海洋群系的 {@link MultiNoiseBiomeSource}。
     *
     * <p>每个条目是一个"气候区间 → 群系"的映射。这里只让**温度**与
     * **深度（continentalness）**参与区分，其余维度取全区间，
     * 保证任何坐标都能命中一个海洋群系（最后还兜底一个 ocean）。
     */
    private static MultiNoiseBiomeSource oceanBiomeSource(HolderGetter<Biome> biomes) {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> points = new ArrayList<>();
        Climate.Parameter any = Climate.Parameter.span(-1.0F, 1.0F);

        for (int band = 0; band < TEMPERATURE_BANDS.length; band++) {
            Climate.Parameter temperature =
                    Climate.Parameter.span(TEMPERATURE_BANDS[band][0], TEMPERATURE_BANDS[band][1]);
            for (int depth = 0; depth < 2; depth++) {
                Climate.Parameter continents = depth == 0
                        ? Climate.Parameter.span(-1.2F, DEEP_THRESHOLD)      // 深海
                        : Climate.Parameter.span(DEEP_THRESHOLD, SHORE_THRESHOLD); // 浅海
                String id = OCEAN_BIOMES.get(band * 2 + depth);
                biomes.get(ResourceKey.create(Registries.BIOME,
                                net.minecraft.resources.Identifier.withDefaultNamespace(id)))
                        .ifPresent(biome -> points.add(Pair.of(
                                Climate.parameters(temperature, any, continents, any, any, any, 0.0F),
                                biome)));
            }
        }

        /*
         * 这里**故意不加"全覆盖兜底条目"**。
         *
         * 第一版加了一个 span(-1,1) × 6 的兜底（怕区间有缝会找不到群系），
         * 结果它把其它群系全吞了 —— 用户实测"海洋群系只有常规海洋，没有别的"。
         * 原因：ParameterList 是找**距离最近**的条目，而一个覆盖全空间的条目
         * 距离恒为 0，会和更具体的条目打平，谁被选中取决于 R 树结构。
         *
         * 不加也不会出错：ParameterList.findValue 的 R 树在列表非空时
         * 一定会返回一个结果（返回最近的），不存在"找不到群系"。
         * 而上面的温度区间已经完整覆盖 [-1, 1]，
         * continents 也被我们压缩到 [-1.03, -0.15]（落在 [-1.2, -0.11] 内），
         * 所以每个坐标都能精确命中一个海洋群系。
         */
        if (points.isEmpty()) {
            throw new IllegalStateException("没有任何海洋群系可用（生物群系注册表异常）");
        }
        return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(points));
    }

    // ------------------------------------------------------------------
    // 噪声路由器
    // ------------------------------------------------------------------

    /**
     * 把主世界的路由器改造成"一定是海"的版本。
     *
     * <p>改动两处：
     * <ul>
     *   <li>{@code continents} —— 压缩到远海区间 {@code [-0.95, -0.15]}，
     *       供群系选择用（深海/浅海）；</li>
     *   <li>{@code finalDensity} —— 换成一个受控函数，海床恒定在海平面以下。</li>
     * </ul>
     * 其余（温度/湿度/侵蚀/洞穴/含水层/矿脉）保持原版，
     * 这样海底地形、洞穴、沉船结构仍然正常。
     */
    private static NoiseRouter oceanRouter(NoiseRouter vanilla,
                                           HolderGetter<NormalNoise.NoiseParameters> noises) {
        // 原版 continentalness 大约在 [-1.2, 1.0]。
        // 乘 0.4 再平移 -0.55 → 落在 [-1.03, -0.15]，全部是海洋区间，
        // 且保留了"哪里更靠外海"的变化，深海/浅海能自然过渡。
        DensityFunction continents = DensityFunctions.add(
                DensityFunctions.constant(-0.55),
                DensityFunctions.mul(DensityFunctions.constant(0.40), vanilla.continents()));

        // 海床起伏的细节噪声：复用原版的侵蚀噪声（3D），
        // 它的频率正好适合做海底的中小尺度起伏。
        DensityFunction detail = DensityFunctions.mul(
                DensityFunctions.constant(0.10),
                DensityFunctions.noise(noises.getOrThrow(
                        net.minecraft.world.level.levelgen.Noises.EROSION)));

        DensityFunction finalDensity = seabedDensity(continents, detail);
        DensityFunction depth = DensityFunctions.yClampedGradient(
                -64, SEA_LEVEL, -1.0, 1.0);

        return new NoiseRouter(
                vanilla.barrierNoise(),
                vanilla.fluidLevelFloodednessNoise(),
                vanilla.fluidLevelSpreadNoise(),
                vanilla.lavaNoise(),
                vanilla.temperature(),
                vanilla.vegetation(),
                continents,          // ← 改造点 1：群系选择
                vanilla.erosion(),
                depth,               // ← 与自建海床一致的深度函数（供表面规则判断水下）
                vanilla.ridges(),
                vanilla.preliminarySurfaceLevel(),
                finalDensity,        // ← 改造点 2：地形
                vanilla.veinToggle(),
                vanilla.veinRidged(),
                vanilla.veinGap());
    }

    /**
     * 海床密度函数：值为正 = 实心，为负 = 水/空气。
     *
     * <p>做法是把一条 y 方向的斜坡上下平移：
     * <pre>
     *   密度(y) = 斜坡(y) + 常量偏移 + 深度偏移 + 细节噪声
     * </pre>
     * 斜坡过零点就是海床高度。常量偏移把过零点压到海平面以下
     * {@link #BASE_DEPTH} 格；深度偏移让"外海更深"；细节噪声制造小起伏。
     *
     * <p>振幅经过计算，保证过零点**始终低于海平面** ——
     * 这是"整个世界都是海、不会冒出天然陆地"的关键。
     */
    private static DensityFunction seabedDensity(DensityFunction continents,
                                                 DensityFunction detail) {
        NoiseSettings settings = NoiseSettings.create(-64, 384, 1, 2);
        int minY = settings.minY();
        double span = SEA_LEVEL - minY;                 // 斜坡覆盖的垂直范围
        double slope = 2.0 / span;                      // 每格密度变化量

        // 把过零点从斜坡中点推到"海平面下 BASE_DEPTH 格"
        double targetY = SEA_LEVEL - BASE_DEPTH;
        double shift = slope * (targetY - (minY + span / 2.0));

        // continents ∈ [-1.03, -0.15]，中点约 -0.59。
        // 越靠外海（更负）→ 海床越深。映射到 ±DEPTH_VARIATION 格。
        DensityFunction depthOffset = DensityFunctions.mul(
                DensityFunctions.constant(slope * DEPTH_VARIATION / 0.44),
                DensityFunctions.add(continents, DensityFunctions.constant(0.59)));

        // 细节噪声同样换算成"格 → 密度"
        DensityFunction detailOffset = DensityFunctions.mul(
                DensityFunctions.constant(slope * DETAIL_VARIATION / 0.10), detail);

        DensityFunction base = DensityFunctions.yClampedGradient(
                minY, SEA_LEVEL, 1.0, -1.0);
        return DensityFunctions.add(
                DensityFunctions.add(DensityFunctions.add(base,
                        DensityFunctions.constant(shift)), depthOffset),
                detailOffset);
    }

    /**
     * 海平面（噪声设置里的参考值）。
     *
     * <p>注意它**不等于最上层水方块的高度** —— 原版的语义是"水面在这个高度"，
     * 水方块本身占到此高度以下。要判断"水面上方一格"请用 {@link #waterSurface()}。
     */
    public static int seaLevel() {
        return SEA_LEVEL;
    }

    /**
     * 最上层**水方块**的高度。
     *
     * <p>实测这个世界是 Y=62（seaLevel=63）。岛屿顶面要"高出水面一格"，
     * 就应该放在 63 —— 用 seaLevel 当水面会高出一格，
     * 这正是用户反馈"岛屿高出海平面 2 格"的来源。
     */
    public static int waterSurface() {
        return SEA_LEVEL - 1;
    }
}
