package cn.dreamgary.hubsuite.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Map;

/**
 * {@code MinecraftServer.levels} 是 {@code private final Map}。
 *
 * <p>本项目的"每子服独立存档"方案需要把自己的 {@link ServerLevel} 放进这张表，
 * 放进去之后原版就会自动帮它 tick、保存、发送世界信息 —— 不需要再另写一套调度。
 * 这是整个方案里唯一必须触碰 MinecraftServer 内部结构的地方。
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerLevelsAccessor {

    @Accessor("levels")
    Map<ResourceKey<Level>, ServerLevel> hubsuite$levels();

    /**
     * {@code MinecraftServer.setInitialSpawn} 是 private static。
     *
     * <p>原版只在创建**主世界**时调用它来算地表出生点，自定义维度不会自动算。
     * 我们的子服是自定义维度，所以必须显式调用它，才能让原版帮我们找出
     * "实体地面 + 上方无遮挡"的出生位置 —— 这正是"正常出生在地形上"的正确做法，
     * 比人工指定 Y 坐标或铺平台都可靠。
     */
    @Invoker("setInitialSpawn")
    static void hubsuite$setInitialSpawn(ServerLevel level, ServerLevelData data,
                                         boolean generateBonusChest, boolean isDebug,
                                         LevelLoadListener listener) {
        throw new AssertionError();
    }
}
