package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.rod.RodData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 带我们数据的钓竿：**原版的耐久扣减一律不生效**，耐久归 {@link RodData} 管。
 *
 * <p><b>为什么必须拦</b>：{@code FishingRodItem.use} 收竿时的顺序是
 * {@code retrieve(...)}（我们的逻辑在里面扣耐久、把耐久条按比例映射到原版条）
 * → 紧接着 {@code stack.hurtAndBreak(1, ...)}。我们的映射会把"已坏"的竿的耐久条
 * 放在 {@code MAX_DAMAGE - 1}，原版这最后一下 +1 正好打满 →
 * **物品被原版销毁**，跟方案里"耐久归零只是不能钓、物品不消失"直接矛盾。
 *
 * <p>拦在私有的 {@code applyDamage} 上：三个 {@code hurtAndBreak} 重载最后都汇到这里，
 * 一个注入点覆盖全部来源。普通原版钓竿（没有我们的数据）**一行都不碰**。
 */
@Mixin(ItemStack.class)
public abstract class RodDurabilityMixin {

    @Inject(method = "applyDamage", at = @At("HEAD"), cancellable = true)
    private void hubsuite$rodDurabilityIsOurs(int amount, ServerPlayer player, Consumer<Item> onBreak,
                                              CallbackInfo ci) {
        if (RodData.of((ItemStack) (Object) this) != null) {
            ci.cancel();
        }
    }
}
