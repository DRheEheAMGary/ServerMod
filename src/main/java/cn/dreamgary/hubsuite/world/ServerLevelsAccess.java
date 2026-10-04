package cn.dreamgary.hubsuite.world;

import net.minecraft.resources.ResourceKey;
import cn.dreamgary.hubsuite.mixin.MinecraftServerLevelsAccessor;
import cn.dreamgary.hubsuite.mixin.MinecraftServerStorageAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 通过 Mixin 访问器安全地往服务端的维度表里增删维度。
 *
 * <p>把逻辑集中在这里，是为了让"我们必须碰一次内部结构"这件事只有一个出口，
 * 以后 MC 改结构时只需要改这一个文件。
 */
public final class ServerLevelsAccess {

    private ServerLevelsAccess() {
    }

    @SuppressWarnings("unchecked")
    private static MinecraftServerLevelsAccessor access(MinecraftServer server) {
        return (MinecraftServerLevelsAccessor) (Object) server;
    }

    /** 注册一个新维度（会立刻参与 tick / 保存 / 玩家追踪）。 */
    public static void put(MinecraftServer server, ServerLevel level) {
        access(server).hubsuite$levels().put(level.dimension(), level);
    }

    /** 移除维度。注意：调用方需要自己负责先卸载玩家、保存区块。 */
    public static void remove(MinecraftServer server, ResourceKey<Level> key) {
        access(server).hubsuite$levels().remove(key);
    }

    public static boolean contains(MinecraftServer server, ResourceKey<Level> key) {
        return access(server).hubsuite$levels().containsKey(key);
    }
}
