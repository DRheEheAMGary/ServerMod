package cn.dreamgary.hubsuite.rod;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * 装配台的 id 与属性（两端**必须一致**）。
 *
 * <p>为什么只放 id 和属性、不放方块类：方块类要用 Polymer（服务端专属依赖），
 * 而 {@code common/} 的代码会被编进客户端 jar —— 客户端拿不到 Polymer。
 * 所以服务端的方块是 {@code BaseEntityBlock + PolymerBlock}，客户端注册一个
 * **同 id、同属性的普通方块**（客户端只需要能渲染它）。方块与物品一样不参与网络同步，
 * 两端各注册一份；方块状态的 id 由 Fabric 的注册表同步对齐。
 */
public final class AssemblyTableIds {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("hubsuite", "assembly_table");
    public static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, ID);
    public static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, ID);

    private AssemblyTableIds() {
    }

    /** 方块属性（两端必须一样，否则状态数/属性对不上）。 */
    public static Block.Properties blockProperties() {
        return Block.Properties.of().setId(BLOCK_KEY).strength(2.5F);
    }

    /** 方块物品的属性。 */
    public static Item.Properties itemProperties() {
        return new Item.Properties()
                .setId(ITEM_KEY)
                .component(DataComponents.ITEM_NAME, Component.translatable("block.hubsuite.assembly_table"));
    }
}
