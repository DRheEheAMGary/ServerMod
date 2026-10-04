package cn.dreamgary.hubsuite.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.Executor;

/**
 * {@code MinecraftServer.storageSource} 是 {@code protected}、{@code executor} 是 {@code private}。
 *
 * <p>本项目的多世界方案需要：以主世界存档为基准，为每个子服创建自己的
 * {@link LevelStorageSource.LevelStorageAccess}，并用与主世界相同的
 * 后台执行器去构造 {@link net.minecraft.server.level.ServerLevel}
 * （区块生成 / 光照都在这个执行器上跑）。这两个字段因此必须可读。
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerStorageAccessor {

    @Accessor("storageSource")
    LevelStorageSource.LevelStorageAccess hubsuite$storageSource();

    @Accessor("executor")
    Executor hubsuite$executor();
}
