package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.world.LevelSeeds;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让每个维度用**自己的**地形种子，而不是全服那一份。
 *
 * <p><b>原版行为：</b>{@code ServerLevel.getSeed()} 返回的是
 * {@code server.getWorldGenSettings().options().seed()} —— 全服一份。
 * 而 {@code ChunkMap} 构造时正是用它建 {@code RandomState}：
 * <pre>
 *   ChunkMap.&lt;init&gt;:
 *     ServerLevel.getSeed()  →  RandomState.create(generatorSettings, noises, seed)
 * </pre>
 * 于是同一个服务器里**所有维度生成的地形完全相同**（同样的噪声、矿脉、结构）。
 *
 * <p><b>本模组为什么要改：</b>每个子服是"一个独立存档、一张独立地图"，
 * 配置里也给了各自的 {@code seed}。但那个值以前只喂给了
 * {@code BiomeManager.obfuscateSeed(...)}（只影响群系分布），
 * 地形管线根本没拿到它 —— 实测结果就是"生存服和创造服是同一张地图"。
 *
 * <p>这里把返回值换成 {@link LevelSeeds} 里登记的种子；
 * 没登记过的维度（原版主世界/下界/末地等）原样返回，**不改变原版行为**。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSeedMixin {

    @ModifyReturnValue(method = "getSeed", at = @At("RETURN"))
    private long hubsuite$useOwnSeed(long original) {
        Long own = LevelSeeds.seedOf((ServerLevel) (Object) this);
        return own != null ? own : original;
    }
}
