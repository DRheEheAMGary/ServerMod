package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.feature.RodAnvil;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 铁砧升级（方案第 4 节）：钓竿 + 升级物 → 打了升级的竿。
 *
 * <p>挂在 {@code AnvilMenu.createResult} 的**尾部**：先让原版把结果算完，
 * 我们的组合才覆盖结果格与花费 —— 原版的合并 / 命名 / 附魔全都原样保留，
 * 不归我们管的组合一个字都不改。
 *
 * <p>花费写进原版的 {@code cost} 数据槽，所以**扣等级与累计惩罚都是原版那套**。
 *
 * <p>踩过的坑：{@code inputSlots} 是**父类** {@code ItemCombinerMenu} 的字段，
 * 在 {@code AnvilMenu} 上 {@code @Shadow} 会直接让 mixin 应用失败、服务端起不来
 * （{@code @Shadow field inputSlots was not located in the target class}）。
 * 这里改成走公开的 {@code getSlot(...)}，一个字段都不用影子。
 */
@Mixin(AnvilMenu.class)
public abstract class AnvilRodUpgradeMixin {

    @Shadow
    private DataSlot cost;

    @Inject(method = "createResult", at = @At("TAIL"))
    private void hubsuite$rodUpgrade(CallbackInfo ci) {
        try {
            AnvilMenu self = (AnvilMenu) (Object) this;
            ItemStack left = self.getSlot(AnvilMenu.INPUT_SLOT).getItem();
            ItemStack right = self.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem();
            RodAnvil.Outcome outcome = RodAnvil.combine(left, right);
            if (outcome.isEmpty()) {
                return;
            }
            self.getSlot(AnvilMenu.RESULT_SLOT).container.setItem(0, outcome.stack());
            cost.set(outcome.cost());
        } catch (Throwable t) {
            HubSuite.logger().debug("铁砧升级算结果失败，保留原版：{}", t.toString());
        }
    }
}
