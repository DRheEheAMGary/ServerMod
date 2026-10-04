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
        return new ServerLevel(
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
    }
}
