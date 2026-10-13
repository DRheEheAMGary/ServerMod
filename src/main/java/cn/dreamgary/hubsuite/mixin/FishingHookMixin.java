package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.feature.FishingGameplay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

/**
 * 钓鱼的两处玩法改动：
 * <ol>
 *   <li><b>岩浆钓鱼</b>：原版 {@code tick} 里直接判 {@code FluidState.is(FluidTags.WATER)}，
 *       岩浆里永远进不了"浮漂"状态。这里对**熔岩钓竿抛出的、且维度配了渔获表**的钩子
 *       放行岩浆；</li>
 *   <li><b>副手钓饵加速</b>：按概率额外递减一次咬钩计时器，等效"等待时间 ×(1-speedup)"；
 *       消耗在钓上来时做（见 {@code FishingHookLootMixin}）。</li>
 * </ol>
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin implements LavaFishingHook {

    @Unique
    private boolean hubsuite$lavaRod;

    @Override
    public boolean hubsuite$fromLavaRod() {
        return hubsuite$lavaRod;
    }

    @Override
    public void hubsuite$setFromLavaRod(boolean value) {
        hubsuite$lavaRod = value;
    }

    @ModifyExpressionValue(method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean hubsuite$lavaCountsAsWater(boolean original) {
        if (original) {
            return true;
        }
        return FishingGameplay.canFishInLava((FishingHook) (Object) this);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void hubsuite$baitSpeed(CallbackInfo ci) {
        FishingHook self = (FishingHook) (Object) this;
        if (!(self.level() instanceof ServerLevel)) {
            return;
        }
        double speedup = FishingGameplay.baitSpeedup(self);
        if (speedup <= 0 || self.getRandom().nextDouble() >= speedup) {
            return;
        }
        FishingHookAccessor accessor = (FishingHookAccessor) (Object) this;
        if (accessor.hubsuite$getTimeUntilHooked() > 0) {
            accessor.hubsuite$setTimeUntilHooked(accessor.hubsuite$getTimeUntilHooked() - 1);
        }
        if (accessor.hubsuite$getTimeUntilLured() > 0) {
            accessor.hubsuite$setTimeUntilLured(accessor.hubsuite$getTimeUntilLured() - 1);
        }
    }
}
