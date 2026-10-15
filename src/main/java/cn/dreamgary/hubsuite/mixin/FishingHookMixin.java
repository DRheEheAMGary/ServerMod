package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.feature.FishingGameplay;
import cn.dreamgary.hubsuite.feature.LavaFishingHook;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
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
    private boolean hubsuite$lavaCapable;

    @Override
    public boolean hubsuite$lavaCapable() {
        return hubsuite$lavaCapable;
    }

    @Override
    public void hubsuite$setLavaCapable(boolean value) {
        hubsuite$lavaCapable = value;
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
    @ModifyExpressionValue(method = {"tick", "getOpenWaterTypeForBlock"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z"))
    private boolean hubsuite$lavaCountsAsWater(boolean original) {
        if (original) {
            return true;
        }
        return FishingGameplay.canFishInLava((FishingHook) (Object) this);
    }

    /**
     * 岩浆里别冒水花：把 {@code catchingFish} 里的 SPLASH / BUBBLE 粒子换成岩浆粒子。
     *
     * <p>用户要求"粒子改成岩浆"。只在"熔岩钓竿抛的钩子且真的泡在岩浆里"时替换，
     * 普通水里还是原版水花。
     */
    @ModifyExpressionValue(method = "catchingFish",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/core/particles/ParticleTypes;SPLASH:Lnet/minecraft/core/particles/SimpleParticleType;",
                    opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private net.minecraft.core.particles.SimpleParticleType hubsuite$lavaSplash(
            net.minecraft.core.particles.SimpleParticleType original) {
        return hubsuite$inLavaForEffects()
                ? net.minecraft.core.particles.ParticleTypes.LAVA : original;
    }

    @ModifyExpressionValue(method = "catchingFish",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/core/particles/ParticleTypes;BUBBLE:Lnet/minecraft/core/particles/SimpleParticleType;",
                    opcode = org.objectweb.asm.Opcodes.GETSTATIC))
    private net.minecraft.core.particles.SimpleParticleType hubsuite$lavaBubble(
            net.minecraft.core.particles.SimpleParticleType original) {
        return hubsuite$inLavaForEffects()
                ? net.minecraft.core.particles.ParticleTypes.LAVA : original;
    }

    @Unique
    private boolean hubsuite$inLavaForEffects() {
        return FishingGameplay.canFishInLava((FishingHook) (Object) this);
    }

    /**
     * 熔岩竿的钩子泡在岩浆里时**不着火**（用户要求："钓钩不要着火"）。
     *
     * <p>必须在 {@code tick} 的 **TAIL** 清：{@code FishingHook.tick} 内部会调
     * {@code super.tick()} → {@code Entity.baseTick()} → {@code lavaHurt()}，
     * 那里每 tick 都会 {@code setRemainingFireTicks(15)} —— 在 HEAD 清等于白清。
     * {@code FishingHook} 不是 LivingEntity，所以只是"看起来着火"，清掉即可。
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void hubsuite$noFireInLava(CallbackInfo ci) {
        FishingHook self = (FishingHook) (Object) this;
        // 快路径：绝大多数情况这里就是 0（没火），直接返回，一次字段读而已。
        // 正常路径由 EntityLavaHurtMixin（fireImmune）挡住，这里只是双保险。
        if (self.getRemainingFireTicks() == 0) {
            return;
        }
        if (((LavaFishingHook) (Object) this).hubsuite$lavaCapable()
                && FishingGameplay.inFluid(self, net.minecraft.tags.FluidTags.LAVA)) {
            self.clearFire();
        }
    }

    /**
     * 原版 {@code FishingHook.shouldStopFishing(Player)} 是**身份比较**：
     *
     * <pre>
     * boolean mainRod = main.getMainHandItem().is(Items.FISHING_ROD);
     * boolean offRod  = player.getOffhandItem().is(Items.FISHING_ROD);
     * if ((mainRod || offRod) &amp;&amp; distanceToSqr(player) &lt;= 1024) return false;
     * this.discard();   // ← 手里不是**原版**钓竿就立刻收掉
     * </pre>
     *
     * <p><b>历史</b>：0.5 的熔岩钓竿是自有 id（{@code hubsuite:lava_rod}），这个检查必然为假 ——
     * 症状就是实测的<b>"一甩竿浮标就消失，什么都没碰到"</b>（浮标下一 tick 就被 {@code discard()}）。
     * 0.6 取消了那个物品（岩浆改看钩），现在带数据的竿就是**原版钓竿**，原版检查本来就过 ——
     * 但这段"带数据的竿也算竿"仍然留着：以后再加"自有 id 的竿"时不会再踩同一个坑。
     * 只接管"手里确实拿着我们的竿"的情况：继续钓；距离超 1024 / 玩家不能交互仍按原版收掉。
     */
    @Inject(method = "shouldStopFishing", at = @At("HEAD"), cancellable = true)
    private void hubsuite$ourRodCountsAsRod(Player player, CallbackInfoReturnable<Boolean> cir) {
        if (!player.canInteractWithLevel()) {
            return;
        }
        boolean ours = cn.dreamgary.hubsuite.rod.RodData.of(player.getMainHandItem()) != null
                || cn.dreamgary.hubsuite.rod.RodData.of(player.getOffhandItem()) != null;
        if (!ours) {
            return;
        }
        FishingHook self = (FishingHook) (Object) this;
        if (self.distanceToSqr(player) <= 1024.0) {
            cir.setReturnValue(false);
        }
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
