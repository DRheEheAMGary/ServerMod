package cn.dreamgary.hubsuite.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.List;

/**
 * 按 vanilla 的方式创建一个 {@link ServerLevel}。
 *
 * <p>这里只做"参数拼装"，不关心业务；每个子服/大厅各自的存档、规则、维度类型
 * 由调用方（{@code SubServer} / {@code Lobby}）决定。
 */
public final class WorldBuilder {

    private WorldBuilder() {
    }

    /** 用 hubsuite 命名空间拼维度 key。 */
    public static ResourceKey<Level> dimensionKey(String path) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("hubsuite", path));
    }

    /**
     * @param tickTime 是否让这个世界自己推进游戏刻时间。
     *                 传 true 时每个子服有自己的昼夜；传 false 时时间由别的维度带动。
     */
    public static ServerLevel create(MinecraftServer server,
                                     IsolatedSave save,
                                     ResourceKey<Level> dimension,
                                     LevelStem stem,
                                     long seed,
                                     boolean tickTime) {
        ServerLevel level = new ServerLevel(
                server,
                ServerInternals.executor(server),
                save.access(),
                save.worldData().overworldData(),
                dimension,
                stem,
                false,
                BiomeManager.obfuscateSeed(seed),
                List.of(),
                tickTime);
        /*
         * 登记这个维度自己的**地形种子**。
         *
         * 上面那个 seed 只喂给了 BiomeManager（**群系分布**）；地形种子在
         * ServerLevel.getSeed() 里是"全服一份"的，于是所有维度会生成同一张地形。
         * 这里把它登记下来，由 ServerLevelSeedMixin 让 getSeed() 返回它 ——
         * ChunkMap 建 RandomState 用的正是 getSeed()，噪声/矿脉/结构就都跟着各自种子走了。
         */
        LevelSeeds.register(level, seed);
        return level;
    }
}
