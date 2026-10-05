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

    /**
     * 海床起伏幅度（格）。
     *
     * <p>必须保证 {@code BASE_DEPTH - DEPTH_VARIATION - DETAIL_VARIATION} 与
     * {@code BASE_DEPTH + ...} 都落在海平面以下 —— 一旦海床有机会高过海平面，
     * 就会冒出天然陆地（而"到处都是海"正是这个维度存在的意义）。
     */
    private static final double DEPTH_VARIATION = 8.0;

    /**
     * 细节噪声幅度（格），做海底的中小尺度起伏。
     *
     * <p>中尺度 + 细尺度两个噪声各占一半，合起来幅度约 ±1，
     * 乘上这个系数就是"格"。
     */
    private static final double DETAIL_VARIATION = 4.0;

    /** 世界最低层（与 NoiseSettings.create(-64, 384, 1, 2) 对应）。 */
    private static final int MIN_Y = -64;

    /** 世界最高层。 */
    private static final int MAX_Y = MIN_Y + 384;

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
                    /*
                     * 关掉含水层。
                     *
                     * 含水层是原版为"洞穴里有水"设计的系统，它会**覆盖**我们的
                     * 密度函数来决定流体高度，结果与"整片世界都是海"的目标打架：
                     * 实测出现大片齐平的沙地平台（用户截图）。
                     * 关掉之后原版走最简单的规则 —— 密度 < 0 且低于海平面就是水，
                     * 正是海洋世界想要的。
                     */
                    false,
                    // 矿脉同理：它按原版地形的高度分布设计，在我们的密度下没有意义
                    false,
                    vanilla.useLegacyRandomSource());

            var biomeSource = oceanBiomeSource(biomes);
            HubSuite.logger().info("海洋世界生成器已就绪：海洋群系 {} 种（4 个温度带 × 深浅两档，"
                            + "暖水的深海共用 deep_lukewarm_ocean），海平面 Y={}，水面 Y={}，海床 Y≈{}~{}",
                    OCEAN_BIOME_SET.size(), SEA_LEVEL, waterSurface(),
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
     *
     * <p>注意最后一个温度带（暖水）的**深海档也是 {@code deep_lukewarm_ocean}** ——
     * 这不是复制粘贴错误：原版根本没有 {@code deep_warm_ocean} 这个群系，
     * 暖水的深海用的就是 {@code deep_lukewarm_ocean}（原版群系预设里就是这么写的）。
     */
    private static final List<String> OCEAN_BIOMES = List.of(
            "deep_frozen_ocean", "frozen_ocean",
            "deep_cold_ocean", "cold_ocean",
            "deep_lukewarm_ocean", "lukewarm_ocean",
            "deep_lukewarm_ocean", "warm_ocean");

    /**
     * 这个维度**真正会生成**的海洋群系 id（不含 {@code minecraft:} 前缀）。
     *
     * <p><b>所有"这里是不是海洋"的判断都必须用这一份集合。</b>
     * 踩过的坑：海岛选位与海洋出生点各写了一份自己的"海洋群系名单"，
     * 两份都和这里对不上 ——
     * <ul>
     *   <li>名单里有 {@code ocean} / {@code deep_ocean} / {@code deep_warm_ocean}，
     *       而这三个在原版注册表里要么根本不存在（{@code deep_warm_ocean}），
     *       要么被我们的群系源排除掉了（前两个），**永远判不到**；</li>
     *   <li>名单里缺 {@code frozen_ocean} / {@code deep_frozen_ocean}，
     *       于是岛一旦落在冰冻温度带就被判成"不是海洋"，
     *       选位器只好一圈圈往外绕（实测 12 个候选点全被否，绕到第二圈才成）。</li>
     * </ul>
     * 现在只留这一份，谁要判断海洋就问这里。
     */
    public static java.util.Set<String> oceanBiomeIds() {
        return OCEAN_BIOME_SET;
    }

    /** 给定群系 id 的 path（不带命名空间）是不是本维度会生成的海洋群系。 */
    public static boolean isOceanBiome(String path) {
        return path != null && OCEAN_BIOME_SET.contains(path);
    }

    private static final java.util.Set<String> OCEAN_BIOME_SET =
            java.util.Set.copyOf(OCEAN_BIOMES);

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
     * 而温度区间与深度区间都是完整覆盖，所以任何坐标都能命中一个海洋群系。
     *
     * <p>想知道"这里算不算海洋"请用 {@link #isOceanBiome(String)}，
     * 不要另外抄一份名单（踩过坑，见那里的说明）。
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

        /*
         * 海床起伏用**两个尺度**的噪声叠加。
         *
         * 踩过的坑：一开始只用原版 EROSION 噪声（第一八度 -9，波长约 512 格），
         * 频率太低 —— 用户截图里海床是一整片齐平的沙地
         * （自检实测"海床完全齐平，都是 Y=41"）。
         *
         * DensityFunctions.noise(holder, xzScale, yScale) 会把采样坐标乘上倍数，
         * 相当于提高频率。这里叠一个中尺度（波长约 100 格）和一个细尺度
         * （波长约 30 格），做出自然的丘陵感。
         */
        /*
         * 频率要**贴近原版**：原版海底的起伏尺度是几百格一级，
         * 近看很平滑，不会出现密集的小疙瘩。
         *
         * 第一版用了 xz×5 与 xz×16（波长约 100 / 30 格），用户反馈
         * "海床太崎岖了，应该和原版一样的平滑"。这里降到 xz×1.5 与 xz×4
         * （波长约 340 / 128 格），并把细尺度的权重压到 1/3。
         */
        DensityFunction medium = DensityFunctions.mul(
                DensityFunctions.constant(0.67),
                DensityFunctions.noise(noises.getOrThrow(
                        net.minecraft.world.level.levelgen.Noises.EROSION), 1.5, 1.0));
        DensityFunction fine = DensityFunctions.mul(
                DensityFunctions.constant(0.33),
                DensityFunctions.noise(noises.getOrThrow(
                        net.minecraft.world.level.levelgen.Noises.SURFACE), 4.0, 2.0));
        DensityFunction detail = DensityFunctions.add(medium, fine);

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
        /*
         * 用**整个世界高度**的渐变，而不是 [minY, seaLevel]。
         *
         * 踩过的坑：一开始渐变只覆盖到海平面，而 yClampedGradient 在区间外会
         * **截断**成端值。于是海平面以上所有高度的密度都等于同一个常数，
         * 只要那个常数大于 0，就会生成"一整片齐平的实心"——
         * 用户截图里那种大片平坦沙地 + 垂直悬崖就是这么来的。
         *
         * 改成覆盖 [minY, maxY] 之后，渐变在整个世界里都是线性的、不会截断，
         * 零 crossing 的位置就严格等于海床高度。
         */
        int minY = MIN_Y;
        int maxY = MAX_Y;
        double span = maxY - minY;                 // 384
        double slope = 2.0 / span;                 // 每格密度变化量

        /*
         * 把零 crossing 放到目标海床高度：
         *   密度(y) = 1 - 2*(y - minY)/span + 偏移
         *   令密度 = 0 → y = minY + (span/2) * (1 + 偏移)
         * 所以  偏移 = 2*(targetY - minY)/span - 1
         */
        double midY = SEA_LEVEL - BASE_DEPTH;      // 海床平均高度（45）
        double shift = 2.0 * (midY - minY) / span - 1.0;

        // continents ∈ [-1.03, -0.15]，中点约 -0.59。
        // 越靠外海（更负）→ 海床越深。换算成"格 → 密度"。
        DensityFunction depthOffset = DensityFunctions.mul(
                DensityFunctions.constant(slope * DEPTH_VARIATION / 0.44),
                DensityFunctions.add(continents, DensityFunctions.constant(0.59)));

        // 细节噪声同样换算（幅度小，只做海底的中小尺度起伏）
        // detail 是两个 ±0.5 噪声之和，幅度约 ±1
        DensityFunction detailOffset = DensityFunctions.mul(
                DensityFunctions.constant(slope * DETAIL_VARIATION), detail);

        DensityFunction base = DensityFunctions.yClampedGradient(
                minY, maxY, 1.0, -1.0);
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
