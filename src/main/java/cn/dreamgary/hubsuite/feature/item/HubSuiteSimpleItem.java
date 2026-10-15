package cn.dreamgary.hubsuite.feature.item;

import cn.dreamgary.hubsuite.net.ClientCompat;
import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 本模组的"简单物品"：**按玩家**决定发什么。
 *
 * <ul>
 *   <li>装了客户端模组且声明了 {@link cn.dreamgary.hubsuite.net.HandshakePayloads#CAP_REAL_ITEMS}
 *       → 发**真物品**（{@code hubsuite:nether_string}），客户端用自己的模型与贴图渲染；</li>
 *   <li>其它客户端 → 保持 Polymer 的原版映射（{@code minecraft:trial_key} + 资源包里的模型），
 *       和以前完全一样。</li>
 * </ul>
 *
 * <p>这就是"渐进迁移"的机制：一个物品一个物品地切过去，切完之前原版客户端一直能用；
 * 全部切完、客户端强制安装之后，Polymer 与资源包一起下线（见 docs/客户端模组方案.md）。
 */
public class HubSuiteSimpleItem extends Item implements PolymerItem {

    /** 给不支持真物品的客户端看的原版物品。 */
    private final Item vanillaLook;

    public HubSuiteSimpleItem(Item.Properties properties, Item vanillaLook) {
        super(properties);
        this.vanillaLook = vanillaLook;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return ClientCompat.useRealItems(context) ? this : vanillaLook;
    }
}
