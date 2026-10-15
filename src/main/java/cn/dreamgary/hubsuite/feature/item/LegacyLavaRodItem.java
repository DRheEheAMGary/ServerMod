package cn.dreamgary.hubsuite.feature.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * <b>遗留物品</b>：0.5 的「熔岩钓竿」，0.6 起不再产出。
 *
 * <p>0.6 把「能不能钓岩浆」从**专用竿**改成了**看钩的材质**（黑曜石 / 下界合金），
 * 所以这个物品本该删掉 —— 但**删不得**：物品一旦从注册表里消失，
 * 老存档里那些 {@code hubsuite:lava_rod} 会被读成空气，玩家的竿凭空蒸发。
 * 折中办法是把它留成**遗留别名**：
 * <ul>
 *   <li>注册还在（老存档读得出来），但配方已删、创造模式也不再给；</li>
 *   <li>样子退回**原版钓竿**（自定义素材已删，靠 {@code ITEM_MODEL} 指过去）；</li>
 *   <li>玩家一进服就会被 {@link cn.dreamgary.hubsuite.feature.LegacyItemMigration}
 *       换成「原版钓竿 + 黑曜石钩」，磨损比例照搬。</li>
 * </ul>
 */
public class LegacyLavaRodItem extends FishingRodItem implements PolymerItem {

    public LegacyLavaRodItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        // 遗留物品，两种客户端都按原版钓竿显示（自定义素材已删）
        return Items.FISHING_ROD;
    }
}
