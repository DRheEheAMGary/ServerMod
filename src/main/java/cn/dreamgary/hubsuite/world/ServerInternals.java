package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.mixin.MinecraftServerLevelsAccessor;
import cn.dreamgary.hubsuite.mixin.MinecraftServerStorageAccessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.util.concurrent.Executor;

/**
 * 访问服务端内部字段的唯一出口：存档源与后台执行器。
 *
 * <p>把这些访问集中在一个文件里，是为了让"我们碰了哪些内部结构"一目了然 ——
 * 升级 Minecraft 时只需要复查这里。
 */
public final class ServerInternals {

    private ServerInternals() {
    }

    private static MinecraftServerStorageAccessor access(MinecraftServer server) {
        return (MinecraftServerStorageAccessor) (Object) server;
    }

    /** 服务端根存档（通常是 {@code <根目录>/world}）的存储访问对象。 */
    public static LevelStorageSource.LevelStorageAccess storageSource(MinecraftServer server) {
        return access(server).hubsuite$storageSource();
    }

    /** 服务端后台执行器（区块生成/光照用）。 */
    public static Executor executor(MinecraftServer server) {
        return access(server).hubsuite$executor();
    }
}
