package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
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
     * 海洋世界：与原版主世界**同样的地形与群系生成**（海洋/暖海/深海/寒冷海洋…），
     * 所以沉船、海底废墟、珊瑚礁这些结构也会照常生成。
     *
     * <p>做法是把主世界那套噪声生成器原样搬过来：
     * 用 {@code NoiseGeneratorSettings.OVERWORLD} + 主世界的群系参数预设。
     * 这样拿到的就是"一片正常生成的海洋"，而不是手工铺出来的水。
     */
    public static ChunkGenerator oceanGenerator(MinecraftServer server) {
        try {
            var biomeRegistry = server.registryAccess().lookupOrThrow(Registries.BIOME);
            var presetRegistry = server.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
            var preset = presetRegistry.getOrThrow(
                    net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD);

            var biomeSource = net.minecraft.world.level.biome.MultiNoiseBiomeSource.createFromPreset(preset);

            var noiseRegistry = server.registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.NOISE_SETTINGS);
            var noiseSettings = noiseRegistry.getOrThrow(
                    net.minecraft.world.level.levelgen.NoiseGeneratorSettings.OVERWORLD);

            HubSuite.logger().info("海洋世界生成器已就绪（群系预设 OVERWORLD，{} 个群系可用）",
                    biomeRegistry.size());
            return new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
                    biomeSource, noiseSettings);
        } catch (Throwable t) {
            HubSuite.logger().error("构造海洋生成器失败，回落到主世界生成器", t);
            return server.overworld().getChunkSource().getGenerator();
        }
    }

    /** 供外部构造 LevelStem 时复用维度类型解析。 */
    public static Holder<DimensionType> dimensionType(MinecraftServer server, String id) {
        return resolveDimensionType(server, id);
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

    /** 纯虚空：单层空气的超平坦生成器（原版内部会自动把 voidGen 标记为 true）。 */
    private static ChunkGenerator voidGenerator(MinecraftServer server) {
        return buildFlat(server, List.of(new FlatLayerInfo(1, Blocks.AIR)));
    }

    /** 超平坦：从配置解析层定义，解析不了就用一层基岩兜底。 */
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
        return buildFlat(server, layers);
    }

    private static ChunkGenerator buildFlat(MinecraftServer server, List<FlatLayerInfo> layers) {
        var biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        HolderGetter<StructureSet> structureSets = server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET);
        HolderGetter<PlacedFeature> placedFeatures = server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);

        FlatLevelGeneratorSettings settings = FlatLevelGeneratorSettings.getDefault(biomes, structureSets, placedFeatures);
        List<FlatLayerInfo> copy = new ArrayList<>(layers);
        settings.getLayersInfo().clear();
        settings.getLayersInfo().addAll(copy);
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
