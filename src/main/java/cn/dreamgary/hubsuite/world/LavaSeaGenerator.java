package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * 海岛服下界的**岩浆海**生成器。
 *
 * <p>做法只有一步：把原版下界的噪声设置搬过来，**把海平面从 32 抬到 96**。
 * 下界的 {@code defaultFluid} 本来就是岩浆，海平面一高，整片低地就全泡在
 * 岩浆里 —— 玩家看到的就是"岩浆海 + 零零散散的下界岩岛"，
 * 而不是原版那种"上下两条岩层中间夹一条岩浆"。
 *
 * <p>其余（噪声路由器、表层规则、群系预设、结构）全部照抄原版下界，
 * 所以地形风格、刷怪、要塞/堡垒该有的都有，只是"海"的形态变了。
 * 构造失败返回 null，调用方回落到虚空。
 */
public final class LavaSeaGenerator {

    /**
     * 岩浆海的液面高度。
     *
     * <p>原版下界是 32（只在下半部分有一层岩浆）；取 96 是为了让"海"占据主导 ——
     * 下界岩地形在 96 以上露头成岛，以下全是岩浆。
     */
    public static final int LAVA_SEA_LEVEL = 96;

    private LavaSeaGenerator() {
    }

    public static net.minecraft.world.level.chunk.ChunkGenerator create(MinecraftServer server) {
        try {
            var access = server.registryAccess();
            NoiseGeneratorSettings vanilla = access.lookupOrThrow(Registries.NOISE_SETTINGS)
                    .getOrThrow(NoiseGeneratorSettings.NETHER).value();

            NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
                    vanilla.noiseSettings(),
                    vanilla.defaultBlock(),
                    vanilla.defaultFluid(),
                    vanilla.noiseRouter(),
                    vanilla.surfaceRule(),
                    vanilla.spawnTarget(),
                    LAVA_SEA_LEVEL,
                    vanilla.disableMobGeneration(),
                    vanilla.aquifersEnabled(),
                    vanilla.oreVeinsEnabled(),
                    vanilla.useLegacyRandomSource());

            var biomeSource = MultiNoiseBiomeSource.createFromPreset(
                    access.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                            .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));

            HubSuite.logger().info("岩浆海生成器已就绪：液面 Y={}（原版下界是 32）", LAVA_SEA_LEVEL);
            return new NoiseBasedChunkGenerator(biomeSource, Holder.direct(settings));
        } catch (Throwable t) {
            HubSuite.logger().error("构造岩浆海生成器失败，该下界将回落到虚空", t);
            return null;
        }
    }
}
