package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.feature.FishingGameplay;
import cn.dreamgary.hubsuite.feature.LavaFishingHook;
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

    /*
     * 这里**故意不写 ordinal**：{@code FishingHook.tick} 里有两处
     * {@code FluidState.is(FluidTags.WATER)}，两处都要改，岩浆钓鱼才成立 ——
     *   · 字节码偏移 127：算水面高度（浮漂的上下浮动），不改就不会"浮"在岩浆上；
     *   · 字节码偏移 595：{@code !is(WATER) && !onGround() && hookedIn == null}
     *     —— 不改就会被当成"出水了"，直接收竿。
     * 所以熔岩竿抛出的钩子在两处都"岩浆也算水"。改之前先 javap 数一遍这两处，
     * 别顺手加 ordinal（加了就只剩一处生效，另一处会把钓鱼掐断）。
     */
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
        if (speedup == 0) {
            return;
        }
        double roll = self.getRandom().nextDouble();
        FishingHookAccessor accessor = (FishingHookAccessor) (Object) this;

        if (speedup > 0) {
            // 加速：基础递减之外，再按概率多减一格 → 等待时间 ×1/(1+speedup)
            if (roll >= speedup) {
                return;
            }
            if (accessor.hubsuite$getTimeUntilHooked() > 0) {
                accessor.hubsuite$setTimeUntilHooked(accessor.hubsuite$getTimeUntilHooked() - 1);
            }
            if (accessor.hubsuite$getTimeUntilLured() > 0) {
                accessor.hubsuite$setTimeUntilLured(accessor.hubsuite$getTimeUntilLured() - 1);
            }
            return;
        }

        /*
         * 减速（环境不匹配的惩罚）：速度 ×(1+speedup)，speedup 是负的。
         *
         * 无法在这里"拦住"原版的递减（注入点在 tick 开头，vanilla 的倒计时在后面跑），
         * 所以反着来：按概率**把计时器加回一格**，等效于有 |speedup| 的比例没递减。
         * 0.40 的惩罚 → 每 tick 有 40% 概率加回 → 实际速度 ×0.6（等待 ×1.67）。
         */
        double slowChance = Math.min(0.9, -speedup);
        if (roll >= slowChance) {
            return;
        }
        if (accessor.hubsuite$getTimeUntilHooked() > 0) {
            accessor.hubsuite$setTimeUntilHooked(accessor.hubsuite$getTimeUntilHooked() + 1);
        }
        if (accessor.hubsuite$getTimeUntilLured() > 0) {
            accessor.hubsuite$setTimeUntilLured(accessor.hubsuite$getTimeUntilLured() + 1);
        }
    }
}
