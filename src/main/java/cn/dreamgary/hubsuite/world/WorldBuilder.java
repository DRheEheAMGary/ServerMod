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
        /*
         * 登记这个维度自己的**地形种子**，而且必须赶在构造之前 ——
         * 这是踩过的坑：
         *
         * 原版的地形种子是全服一份的（{@code ServerLevel.getSeed()} 返回
         * {@code server.getWorldGenSettings().options().seed()}），
         * 而区块系统在 **ServerLevel 构造函数内部**就把它用掉了：
         * <pre>
         *   ServerLevel.&lt;init&gt; → 建 ChunkMap
         *   ChunkMap.&lt;init&gt;   → ServerLevel.getSeed() → RandomState.create(...)
         * </pre>
         * 第一版把 LevelSeeds.register 写在构造**之后**，于是 ChunkMap 拿到的
         * 仍是全服种子 —— 各子服照样生成同一张地形（用户复测："种子还是没生效"）。
         *
         * 现在改成"先登记、后构造"。因为构造期间还没人能拿到这个 level 引用，
         * 所以我把种子按**维度 key**预先登记，构造完再迁移到实例键上。
         */
        LevelSeeds.registerPending(dimension, seed);
        try {
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
            LevelSeeds.bind(level);
            return level;
        } finally {
            LevelSeeds.clearPending(dimension);
        }
    }
}
