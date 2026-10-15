package cn.dreamgary.hubsuite.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 读/写 {@code FishingHook} 的私有咬钩计时器（钓饵加速要用）。 */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {

    @Accessor("timeUntilHooked")
    int hubsuite$getTimeUntilHooked();

    @Accessor("timeUntilHooked")
    void hubsuite$setTimeUntilHooked(int value);

    @Accessor("timeUntilLured")
    int hubsuite$getTimeUntilLured();

    @Accessor("timeUntilLured")
    void hubsuite$setTimeUntilLured(int value);

    /** 自检用：鱼靠近 / 正在咬钩的状态位。 */
    @Accessor("nibble")
    int hubsuite$getNibble();

    @Accessor("biting")
    boolean hubsuite$isBiting();
}
