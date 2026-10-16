package cn.dreamgary.hubsuite.client;

import cn.dreamgary.hubsuite.rod.RodData;
import cn.dreamgary.hubsuite.rod.RodTooltip;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.network.chat.Component;

/**
 * 钓竿的物品提示（客户端）：平时只看**总体数值**，按住 <b>Shift</b> 再展开**每个部件的数值**。
 *
 * <p>为什么走事件而不是覆写 {@code appendHoverText}：钓竿就是**原版** {@code minecraft:fishing_rod}
 * （部件数据写在它的组件里），我们并不拥有那个物品类。
 */
public final class ClientRodTooltip {

    private ClientRodTooltip() {
    }

    public static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            RodData data = RodData.of(stack);
            if (data == null) {
                return;   // 不是我们的竿 → 原版 tooltip 一个字都不动
            }
            lines.addAll(RodTooltip.summary(data));
            if (net.minecraft.client.Minecraft.getInstance().options.keyShift.isDown()) {
                lines.addAll(RodTooltip.details(data));
            }
        });
    }
}
