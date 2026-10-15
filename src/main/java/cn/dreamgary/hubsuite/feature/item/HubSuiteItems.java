package cn.dreamgary.hubsuite.feature.item;

import cn.dreamgary.hubsuite.item.HubSuiteItemDefs;
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
    /*
     * 钓饵本身（bait / nether_bait / 压缩档）在 v0.4.0-alpha.7 已**移除**：
     * 钓饵系统改成"自己注册物品 + 加进 item tag"，判据见
     * {@link cn.dreamgary.hubsuite.feature.FishingBait}：
     *   #hubsuite:fishing_bait        —— 能不能放进渔桶 / 当饵用
     *   #hubsuite:fishing_bait_nether —— 是不是"岩浆那一族"
     * 档次 = 物品稀有度（COMMON 1 / UNCOMMON 2 / RARE 3 / EPIC 4）。
     */
    /** 遗留：0.5 的熔岩钓竿（不再产出，只为老存档里的旧竿还在）。 */
    public static Item LAVA_ROD;
    /** 渔桶：钓饵的收纳袋（容量 16 / 64 / 1024）。 */
    public static Item WOODEN_FISHING_BUCKET;
    public static Item IRON_FISHING_BUCKET;
    public static Item DIAMOND_FISHING_BUCKET;

    private HubSuiteItems() {
    }

    /** 在 {@code onInitialize} 里调一次。 */
    public static void register() {
        NETHER_STRING = register("nether_string", new HubSuiteSimpleItem(
                HubSuiteItemDefs.properties(HubSuiteItemDefs.NETHER_STRING), Items.TRIAL_KEY));

        // 遗留物品：注册着（老存档认得出），但样子退回原版钓竿、配方已删
        LAVA_ROD = register("lava_rod", new LegacyLavaRodItem(props("lava_rod")));

        WOODEN_FISHING_BUCKET = bucket("wooden_fishing_bucket", 64);
        IRON_FISHING_BUCKET = bucket("iron_fishing_bucket", 256);
        DIAMOND_FISHING_BUCKET = bucket("diamond_fishing_bucket", 1024);

        cn.dreamgary.hubsuite.HubSuite.logger().info(
                "已注册 4 个自定义物品（地狱线 / 三种渔桶）+ 1 个遗留别名（熔岩钓竿，只能被换算掉）；钓饵改成 tag 驱动，见 #hubsuite:fishing_bait");
    }

    private static Item bucket(String path, int capacity) {
        return register(path, new BaitBucketItem(props(path), capacity));
    }

    /**
     * 物品属性一律来自 {@code common/} 的共用定义 —— 客户端按同一份原型注册，
     * 否则客户端重建出来的物品栈会和服务端不是同一个东西（见 {@link HubSuiteItemDefs}）。
     */
    private static Item.Properties props(String path) {
        return HubSuiteItemDefs.properties(defOf(path));
    }

    private static HubSuiteItemDefs.Def defOf(String path) {
        for (HubSuiteItemDefs.Def def : HubSuiteItemDefs.ALL) {
            if (def.path().equals(path)) {
                return def;
            }
        }
        throw new IllegalArgumentException("没有这个物品定义：" + path);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }

    private static Item register(String path, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, id(path), item);
    }
}
