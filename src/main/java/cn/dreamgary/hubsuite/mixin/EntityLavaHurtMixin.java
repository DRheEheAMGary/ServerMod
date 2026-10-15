package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.feature.FishingGameplay;
import cn.dreamgary.hubsuite.feature.LavaFishingHook;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 熔岩钓竿抛出的浮标泡在岩浆里时**不着火**。
 *
 * <p>{@code Entity.lavaHurt()} 的第一件事就是 {@code if (fireImmune()) return;}，
 * 所以这里把"我们的熔岩钩子"也算成免疫火焰 —— 比"每 tick 去清一次火焰"更省：
 * <ul>
 *   <li>{@code lavaHurt()} 只在实体**真的泡在岩浆里**时才被调用（不在岩浆里 = 一次都不跑）；</li>
 *   <li>不点火就不会每 tick 抖动实体数据（火焰标记），也就没有额外的同步包；</li>
 *   <li>顺带根治了"清火前先着了一下"的画面闪烁。</li>
 * </ul>
 *
 * <p>{@code FishingHook} 不是 LivingEntity，本来就不会被岩浆烧死，这里只是火焰外观。
 */
@Mixin(Entity.class)
public abstract class EntityLavaHurtMixin {

    @ModifyExpressionValue(method = "lavaHurt",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;fireImmune()Z"))
    private boolean hubsuite$lavaHookFireImmune(boolean original) {
        if (original) {
            return true;
        }
        if (!(((Object) this) instanceof FishingHook hook)) {
            return false;
        }
        return ((LavaFishingHook) hook).hubsuite$lavaCapable()
                && FishingGameplay.inFluid(hook, FluidTags.LAVA);
    }
}
