package cn.dreamgary.hubsuite.feature.item;

import eu.pb4.polymer.core.api.item.SimplePolymerItem;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;

/**
 * 本模组的自定义物品（v0.4.0 起）。
 *
 * <p><b>为什么必须依赖 Polymer：</b>{@code item} 注册表**不在** Minecraft
 * 的网络同步清单里（{@code RegistryDataLoader.SYNCHRONIZED_REGISTRIES}），
 * 原版客户端不认识我们自己注册的物品 id。Polymer 负责在发往客户端的包里
 * 把我们的物品换成**原版物品**（{@code getPolymerItem} → {@code trial_key}），
 * 再用 {@code item_model} 组件指到我们资源包里的模型 —— 于是客户端看到的是
 * "一个长得像地狱线的试炼钥匙"，而服务端持有的是**真正独立 id 的物品**，
 * 配方、统计、NBT 全都正常。
 *
 * <p>显示物品一律用 {@code minecraft:trial_key}：它在生存里少见，不会和
 * 正常玩法里的物品混淆；具体外观由资源包的 {@code hubsuite:item/<id>} 决定。
 */
public final class HubSuiteItems {

    public static final String NAMESPACE = "hubsuite";

    /** 地狱线：熔岩钓竿的合成材料。 */
    public static Item NETHER_STRING;
    /** 普通钓饵 / 地狱钓饵 / 两档压缩。 */
    public static Item BAIT;
    public static Item NETHER_BAIT;
    public static Item COMPRESSED_BAIT;
    public static Item COMPRESSED_NETHER_BAIT;
    public static Item DOUBLE_COMPRESSED_BAIT;
    public static Item DOUBLE_COMPRESSED_NETHER_BAIT;
    /** 能在岩浆里钓的钓竿。 */
    public static Item LAVA_ROD;

    private HubSuiteItems() {
    }

    /** 在 {@code onInitialize} 里调一次。 */
    public static void register() {
        NETHER_STRING = simple("nether_string", Rarity.COMMON);
        BAIT = simple("bait", Rarity.COMMON);
        NETHER_BAIT = simple("nether_bait", Rarity.COMMON);
        COMPRESSED_BAIT = simple("compressed_bait", Rarity.UNCOMMON);
        COMPRESSED_NETHER_BAIT = simple("compressed_nether_bait", Rarity.UNCOMMON);
        DOUBLE_COMPRESSED_BAIT = simple("double_compressed_bait", Rarity.RARE);
        DOUBLE_COMPRESSED_NETHER_BAIT = simple("double_compressed_nether_bait", Rarity.RARE);

        LAVA_ROD = register("lava_rod", new LavaRodItem(props("lava_rod")
                .stacksTo(1)
                .durability(256)
                .rarity(Rarity.RARE)
                .component(DataComponents.ITEM_NAME, Component.translatable("item.hubsuite.lava_rod"))
                .component(DataComponents.ITEM_MODEL, id("lava_rod"))));

        cn.dreamgary.hubsuite.HubSuite.logger().info(
                "已注册 8 个自定义物品（Polymer 硬依赖：原版客户端看到的是试炼钥匙 + 资源包模型）");
    }

    /** 1.21.2+ 的 Item.Properties **必须**先 setId，否则构造物品时会 NPE（实测）。 */
    private static Item.Properties props(String path) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(path)));
    }

    private static Item simple(String path, Rarity rarity) {
        return register(path, new SimplePolymerItem(props(path)
                .rarity(rarity)
                .component(DataComponents.ITEM_NAME, Component.translatable("item.hubsuite." + path))
                .component(DataComponents.ITEM_MODEL, id(path)),
                Items.TRIAL_KEY,
                true));
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    private static Item register(String path, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, id(path), item);
    }
}
