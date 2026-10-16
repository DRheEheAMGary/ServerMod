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
 * <p>为什么只放 id 和属性、不放方块类：客户端那份是**普通方块**（只需要能渲染），
 * 服务端那份是 {@code BaseEntityBlock}（带方块实体行为），两个类不能共用一份源码 ——
 * 但 id 与属性必须一致。两端各注册一份；注册表条目的对齐交给 Fabric 的注册表同步。
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
