package cn.dreamgary.hubsuite.item;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * 本模组自定义物品的**共同定义**（0.5.0 起）。
 *
 * <p>为什么必须共用一份：物品的网络同步只发**与原型不同的组件**
 * （{@code ItemStack} 的组件 patch）。客户端如果按自己的原型重建这个栈，
 * 而原型跟服务端不一致（堆叠上限、稀有度、名字、模型、耐久），
 * 显示的就会跟服务端不是同一个东西。所以两端的 {@code Item.Properties}
 * 必须来自同一份定义 —— 这份定义就放在 {@code common/}，两个 jar 各编译一份。
 *
 * <p>服务端在此之上用 Polymer 的子类（原版客户端映射成 {@code minecraft:trial_key}）；
 * 客户端注册成普通 {@code Item}（自己渲染真物品）。
 */
public final class HubSuiteItemDefs {

    public static final String NAMESPACE = "hubsuite";

    /** 一个物品的定义：id 路径 / 堆叠上限 / 稀有度 / 耐久（0 = 无耐久）。 */
    public record Def(String path, int maxStack, Rarity rarity, int durability) {

        public Identifier id() {
            return Identifier.fromNamespaceAndPath(NAMESPACE, path);
        }

        /** 翻译键 {@code item.hubsuite.<path>}。 */
        public Component name() {
            return Component.translatable("item.hubsuite." + path);
        }
    }

    /** 地狱线：熔岩钓竿的合成材料。 */
    public static final Def NETHER_STRING = new Def("nether_string", 64, Rarity.COMMON, 0);
    /**
     * 遗留：0.5 的熔岩钓竿。**不再产出**，保留注册只为老存档里的旧竿不变成空气
     * （进服时会被换成「原版钓竿 + 黑曜石钩」，见 {@code LegacyItemMigration}）。
     */
    public static final Def LAVA_ROD = new Def("lava_rod", 1, Rarity.RARE, 256);
    /** 渔桶三个档次。 */
    public static final Def WOODEN_FISHING_BUCKET = new Def("wooden_fishing_bucket", 1, Rarity.COMMON, 0);
    public static final Def IRON_FISHING_BUCKET = new Def("iron_fishing_bucket", 1, Rarity.UNCOMMON, 0);
    public static final Def DIAMOND_FISHING_BUCKET = new Def("diamond_fishing_bucket", 1, Rarity.RARE, 0);

    /** 全部物品（顺序即注册顺序）。 */
    public static final List<Def> ALL = List.of(
            NETHER_STRING, LAVA_ROD,
            WOODEN_FISHING_BUCKET, IRON_FISHING_BUCKET, DIAMOND_FISHING_BUCKET);

    private HubSuiteItemDefs() {
    }

    /**
     * 按定义构造 {@code Item.Properties}（两端都用它，保证原型一致）。
     *
     * <p>注意 {@code setId} 必须最先设：1.21.2+ 少了它会构造物品时 NPE（实测踩过）。
     */
    public static Item.Properties properties(Def def) {
        Item.Properties props = new Item.Properties()
                .setId(ResourceKey.create(Registries.ITEM, def.id()))
                .stacksTo(def.maxStack())
                .rarity(def.rarity())
                .component(DataComponents.ITEM_NAME, def.name())
                // 遗留别名没有自己的素材，模型直接指回原版钓竿（两端一致，素材检查会跳过它们）
                .component(DataComponents.ITEM_MODEL,
                        isLegacyAlias(def) ? Identifier.withDefaultNamespace("item/fishing_rod") : def.id());
        if (def.durability() > 0) {
            props = props.durability(def.durability());
        }
        return props;
    }

    /**
     * 是不是**遗留别名**（老存档里还认得出、但已经不再产出、也没有自己素材的物品）。
     *
     * <p>素材齐全性检查要跳过它们：模型指回原版，本来就不需要 `items/` `models/` `textures/` 三件套。
     */
    public static boolean isLegacyAlias(Def def) {
        return def == LAVA_ROD;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }
}
