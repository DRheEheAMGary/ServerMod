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
        return Items.FISHING_ROD;
    }
}
