package cn.dreamgary.hubsuite.feature.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 熔岩钓竿：服务端就是一支正常钓竿，但带上它以后浮漂能在**岩浆**里咬钩
 * （见 {@code mixin/FishingHookLavaMixin}）。
 *
 * <p>客户端侧由 Polymer 显示成原版 {@code minecraft:fishing_rod}
 * （这样"甩竿"状态、钓鱼属性都对），外观模型由 {@code item_model} 组件
 * 指向资源包里的 {@code hubsuite:lava_rod}。
 */
public class LavaRodItem extends FishingRodItem implements PolymerItem {

    public LavaRodItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        // 装了客户端模组（且声明 real_items）的玩家：发真物品，自己渲染（含甩竿态，见 lava_rod.json）；
        // 其余客户端：照旧映射成原版钓竿 + 资源包模型（甩竿/钓鱼属性都对）。
        return cn.dreamgary.hubsuite.net.ClientCompat.useRealItems(context) ? this : Items.FISHING_ROD;
    }

    /**
     * 这个物品栈是不是"我们的钓竿"。
     *
     * <p>用来补原版的**身份比较**：{@code FishingHook.shouldStopFishing} 判的是
     * {@code stack.is(Items.FISHING_ROD)}（原版物品本身），我们的竿是自有 id，
     * 不改就会被当成"手里没拿竿"→ 浮标生成后立刻被 {@code discard()}。
     */
    public static boolean isLavaRod(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof LavaRodItem;
    }
}
