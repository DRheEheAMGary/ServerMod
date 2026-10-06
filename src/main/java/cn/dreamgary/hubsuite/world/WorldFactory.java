package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 按配置构造维度（{@link LevelStem}）。
 *
 * <p>这是"新增子服不用写代码"的关键：三种 {@link HubSuiteConfig.WorldKind}
 * 分别映射到原版已有的区块生成器，不注册任何自定义生成器，因此完全兼容原版与其它模组。
 */
public final class WorldFactory {

    private WorldFactory() {
    }

    /**
     * 构造一个维度定义。
     *
     * @param dimensionTypeId 维度类型 id，例如 {@code minecraft:overworld}、{@code minecraft:the_end}
     */
    public static LevelStem createStem(MinecraftServer server, String dimensionTypeId, HubSuiteConfig.WorldKind kind, List<String> flatLayers) {
        Holder<DimensionType> type = resolveDimensionType(server, dimensionTypeId);
        ChunkGenerator generator = switch (kind) {
            case NORMAL -> server.overworld().getChunkSource().getGenerator();
            case FLAT -> flatGenerator(server, flatLayers);
            case VOID -> voidGenerator(server);
        };
        return new LevelStem(type, generator);
    }

    /**
     * 按**原版自己的方式**造一个下界或末地维度。
     *
     * <p>用途：让每个子服拥有自己独立的下界 / 末地（见
     * {@link PortalLinks}）。做成"和原版一模一样"是关键 ——
     * 群系源、噪声设置、维度类型全部取原版注册表里的同一份，
     * 不自己拼参数，这样地形、结构、刷怪、光照都跟原版下界/末地没有区别。
     *
     * <ul>
     *   <li>下界：{@code Minecraft} 用 {@code NoiseGeneratorSettings.NETHER}
     *       + 下界群系参数预设 {@code MultiNoiseBiomeSourceParameterLists.NETHER}；</li>
     *   <li>末地：{@code NoiseGeneratorSettings.END} + {@code TheEndBiomeSource.create}，
     *       这样才有主岛/外岛的正确分布。</li>
     * </ul>
     *
     * @param nether true 造下界，false 造末地
     * @return 构造好的维度定义；失败返回 null，调用方应跳过该维度
     */
    public static LevelStem vanillaStem(MinecraftServer server, boolean nether) {
        try {
            var access = server.registryAccess();
            var biomes = access.lookupOrThrow(Registries.BIOME);

            net.minecraft.world.level.biome.BiomeSource biomeSource;
            Holder<net.minecraft.world.level.levelgen.NoiseGeneratorSettings> settings;
            Holder<DimensionType> type;
            if (nether) {
                biomeSource = net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(
                        access.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                                .getOrThrow(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.NETHER));
                settings = access.lookupOrThrow(Registries.NOISE_SETTINGS)
                        .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.NETHER);
                type = resolveDimensionType(server, "minecraft:the_nether");
            } else {
                biomeSource = net.minecraft.world.level.biome.TheEndBiomeSource.create(biomes);
                settings = access.lookupOrThrow(Registries.NOISE_SETTINGS)
                        .getOrThrow(net.minecraft.world.level.levelgen.NoiseGeneratorSettings.END);
                type = resolveDimensionType(server, "minecraft:the_end");
            }
            return new LevelStem(type,
                    new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(biomeSource, settings));
        } catch (Throwable t) {
            HubSuite.logger().error("构造原版{}维度失败，将不生成该维度", nether ? "下界" : "末地", t);
            return null;
        }
    }

    /**
     * 海洋世界：与原版主世界**同样的地形与群系生成**（海洋/暖海/深海/寒冷海洋…），
     * 所以沉船、海底废墟、珊瑚礁这些结构也会照常生成。
     *
     * <p>做法是把主世界那套噪声生成器原样搬过来：
     * 用 {@code NoiseGeneratorSettings.OVERWORLD} + 主世界的群系参数预设。
     * 这样拿到的就是"一片正常生成的海洋"，而不是手工铺出来的水。
     */
    /**
     * 海岛维度的区块生成器。
     *
     * <p>实现在 {@link OceanWorldGenerator}：自己组装"只有海洋群系的群系源"
     * 与"保证是海"的噪声路由器。
     *
     * <p>之前这里直接借用主世界的 {@code NoiseGeneratorSettings.OVERWORLD}
     * 和 OVERWORLD 群系预设，结果是**完完全全的常规世界**（有大陆、有森林），
     * 跟"海洋"没关系 —— 实测被用户抓到。
     *
     * @return 海洋生成器；失败时返回 null，调用方应回落到虚空世界
     */
    public static ChunkGenerator oceanGenerator(MinecraftServer server) {
        return OceanWorldGenerator.create(server);
    }

    /** 供外部构造 LevelStem 时复用维度类型解析。 */
    public static Holder<DimensionType> dimensionType(MinecraftServer server, String id) {
        return resolveDimensionType(server, id);
    }

    /**
     * 在海洋世界里找一个"开阔海域"的坐标（只查噪声群系，不生成区块）。
     *
     * <p>用途：让海岛维度的出生点落在真正的海上，而不是原版算出来的陆地 ——
     * 自然生成的世界原点附近很可能是大陆，玩家一进去看到的是平原，
     * "海岛"就名不副实了。
     *
     * @return 找到的坐标；没找到返回 null
     */
    public static net.minecraft.core.BlockPos findOceanSpot(ServerLevel level, int seaLevel) {
        try {
            int quartY = Math.max(0, (seaLevel - 1) >> 2);
            // 从小到大绕圈找，命中就返回（近处优先，省得出生点离原点太远）
            for (int ring = 1; ring <= 48; ring++) {
                int r = ring * 96;
                for (int i = 0; i < 16; i++) {
                    double angle = (Math.PI * 2 / 16) * i + ring * 0.41;
                    int x = (int) Math.round(Math.cos(angle) * r);
                    int z = (int) Math.round(Math.sin(angle) * r);
                    var key = level.getNoiseBiome(x >> 2, quartY, z >> 2).unwrapKey().orElse(null);
                    // 名单只有一份，来自生成器本身：抄第二份必然和生成器对不上
                    // （踩过坑，见 OceanWorldGenerator#oceanBiomeIds 的说明）
                    if (key != null && OceanWorldGenerator.isOceanBiome(key.identifier().getPath())) {
                        HubSuite.logger().info("海洋出生点已选定：({}, {}, {})，群系 {}",
                                x, seaLevel + 1, z, key.identifier());
                        return new net.minecraft.core.BlockPos(x, seaLevel + 1, z);
                    }
                }
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("寻找海洋出生点失败：{}", t.toString());
        }
        return null;
    }

    private static Holder<DimensionType> resolveDimensionType(MinecraftServer server, String id) {
        var registry = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        var key = net.minecraft.resources.ResourceKey.create(
                Registries.DIMENSION_TYPE,
                net.minecraft.resources.Identifier.parse(id));
        return registry.get(key)
                .map(h -> (Holder<DimensionType>) h)
                .orElseGet(() -> {
                    HubSuite.logger().warn("未知维度类型 '{}'，回落到 minecraft:overworld。", id);
                    return registry.getOrThrow(net.minecraft.world.level.dimension.BuiltinDimensionTypes.OVERWORLD);
                });
    }

    /**
     * 纯虚空：单层空气，而且**结构、地物、湖泊全部关掉**。
     *
     * <p><b>踩过的坑（用户实测反馈："空岛服虚空中有村庄结构，两个商铺"）：</b>
     * 以前这里偷懒复用了原版超平坦的默认设置 ——
     * {@code FlatLevelGeneratorSettings.getDefault(...)}，而**原版超平坦默认带结构覆盖**
     * （字节码里明确写了 {@code BuiltinStructureSets.STRONGHOLDS} 和
     * {@code BuiltinStructureSets.VILLAGES}）。于是在一片空气的虚空里，
     * 村庄会照常生成 —— 玩家在大厅平台上就能看到悬空的村庄房屋。
     *
     * <p>现在两件事一起做，保证"空就是空"：
     * <ol>
     *   <li>结构覆盖传 {@code Optional.empty()} —— 不再放行村庄/要塞；</li>
     *   <li>群系用 {@code minecraft:the_void}（原版虚空群系）——
     *       它本身不带任何结构集与地表装饰，作为第二道保险。</li>
     * </ol>
     */
    private static ChunkGenerator voidGenerator(MinecraftServer server) {
        return buildFlat(server, List.of(new FlatLayerInfo(1, Blocks.AIR)), true);
    }

    /** 超平坦：从配置解析层定义，解析不了就用一层基岩兜底。保留原版的结构与地物行为。 */
    private static ChunkGenerator flatGenerator(MinecraftServer server, List<String> rawLayers) {
        List<FlatLayerInfo> layers = new ArrayList<>();
        if (rawLayers != null) {
            for (String raw : rawLayers) {
                FlatLayerInfo info = parseLayer(server, raw);
                if (info != null) {
                    layers.add(info);
                }
            }
        }
        if (layers.isEmpty()) {
            HubSuite.logger().warn("超平坦层配置为空或全部非法，回落到 minecraft:bedrock*1。");
            layers.add(new FlatLayerInfo(1, Blocks.BEDROCK));
        }
        return buildFlat(server, layers, false);
    }

    /**
     * 组装超平坦生成器。
     *
     * @param empty 是否要"纯虚空"语义（无结构、无地物、虚空群系）。
     *              大厅/空岛的虚空维度必须传 true —— 见 {@link #voidGenerator}。
     */
    private static ChunkGenerator buildFlat(MinecraftServer server, List<FlatLayerInfo> layers,
                                            boolean empty) {
        var biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        if (empty) {
            /*
             * 纯虚空：**结构集必须给"显式空集合"，不能给 Optional.empty()**。
             *
             * 两处都踩过：
             *   1) FlatLevelGeneratorSettings.getDefault(...) 的默认结构覆盖就是原版
             *      超平坦那一套，字节码里明确包含 BuiltinStructureSets.STRONGHOLDS 与
             *      VILLAGES —— 于是虚空里长出村庄（用户实测："虚空中有村庄结构，两个商铺"）；
             *   2) 改成 Optional.empty() **也没用**：那表示"没有覆盖"，
             *      原版会回落到**群系自带的结构集**（平原群系有 6 个），照旧生村庄。
             *      自检实测抓到了这一点：4 个虚空维度各放行 6 个结构集。
             *
             * 所以要给 Optional.of(HolderSet.empty()) —— 明确的"一个都不许生成"。
             *
             * 群系保持平原：不能换 minecraft:the_void，那个群系没有刷怪表，
             * 换了大厅就不刷生物了。
             */
            FlatLevelGeneratorSettings voidSettings = new FlatLevelGeneratorSettings(
                    java.util.Optional.of(net.minecraft.core.HolderSet
                            .<StructureSet>empty()),
                    biomes.getOrThrow(Biomes.PLAINS),
                    java.util.List.<Holder<PlacedFeature>>of());
            voidSettings.getLayersInfo().clear();
            voidSettings.getLayersInfo().addAll(layers);
            voidSettings.updateLayers();
            return new FlatLevelSource(voidSettings);
        }
        HolderGetter<StructureSet> structureSets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        HolderGetter<PlacedFeature> placedFeatures = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        FlatLevelGeneratorSettings settings =
                FlatLevelGeneratorSettings.getDefault(biomes, structureSets, placedFeatures);
        settings.getLayersInfo().clear();
        settings.getLayersInfo().addAll(layers);
        settings.updateLayers();
        return new FlatLevelSource(settings);
    }

    /** 解析 {@code minecraft:stone*3} 形式的层定义。 */
    private static FlatLayerInfo parseLayer(MinecraftServer server, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int height = 1;
        int star = text.lastIndexOf('*');
        if (star > 0) {
            try {
                height = Integer.parseInt(text.substring(star + 1).trim());
            } catch (NumberFormatException e) {
                HubSuite.logger().warn("超平坦层 '{}' 的高度部分非法，按 1 处理。", raw);
            }
            text = text.substring(0, star).trim();
        }
        height = Math.max(1, Math.min(height, 256));

        var blockRegistry = server.registryAccess().lookupOrThrow(Registries.BLOCK);
        var key = net.minecraft.resources.ResourceKey.create(
                Registries.BLOCK,
                net.minecraft.resources.Identifier.parse(text));
        Block block = blockRegistry.get(key).map(Holder::value).orElse(null);
        if (block == null) {
            HubSuite.logger().warn("未知方块 '{}'，该层已忽略。", text);
            return null;
        }
        return new FlatLayerInfo(height, block);
    }

    /** 虚空世界用的生物群系（平原：不会生成诡异的地表装饰）。 */
    public static Holder<Biome> plainsBiome(MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
    }

    /** 把配置里的维度类型字符串规范化。 */
    public static String normalizeDimensionType(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
