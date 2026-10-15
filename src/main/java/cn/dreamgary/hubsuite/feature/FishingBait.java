package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.feature.item.BaitBucketItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.Map;

/**
 * 钓饵判定 —— **完全数据驱动**，不硬编码任何具体物品。
 *
 * <p>v0.4.0-alpha.7 起自带的钓饵（bait / nether_bait / 压缩档）全部移除，改成
 * "自己注册物品 + 加进 tag"：
 *
 * <ul>
 *   <li>{@code #hubsuite:fishing_bait} —— <b>能不能放进渔桶 / 能不能当饵用</b>的唯一判据
 *       （文件 {@code data/hubsuite/tags/item/fishing_bait.json}）；</li>
 *   <li>{@code #hubsuite:fishing_bait_nether} —— <b>属于"岩浆那一族"</b>的饵：
 *       只在岩浆里生效、用错环境会减速（没进这个 tag 的都算"水里那一族"）；</li>
 *   <li><b>档次</b>（消耗顺序：高 → 低）用物品的**稀有度**表示：
 *       {@code COMMON=1 / UNCOMMON=2 / RARE=3 / EPIC=4}。</li>
 * </ul>
 *
 * <p>加速值仍由配置 {@code fishing.bait.baitSpeedup / compressedSpeedup /
 * doubleCompressedSpeedup} 按档次 1 / 2 / 3 取（4 档用 3 档的值）。
 */
public final class FishingBait {

    /** 能不能进渔桶 / 能不能当饵用。 */
    public static final TagKey<Item> BAIT = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("hubsuite", "fishing_bait"));

    /** "岩浆那一族"的饵（在岩浆里才生效）。 */
    public static final TagKey<Item> NETHER_BAIT = TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("hubsuite", "fishing_bait_nether"));

    private FishingBait() {
    }

    public static boolean isBait(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(BAIT);
    }

    public static boolean isBait(Item item) {
        return item != null && BuiltInRegistries.ITEM.wrapAsHolder(item).is(BAIT);
    }

    public static boolean isNetherBait(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(NETHER_BAIT);
    }

    public static boolean isNetherBait(Item item) {
        return item != null && BuiltInRegistries.ITEM.wrapAsHolder(item).is(NETHER_BAIT);
    }

    /** 档次（消耗顺序用）：稀有度越高越先消耗。 */
    public static int tierOf(ItemStack stack) {
        return tierOfRarity(stack.getRarity());
    }

    public static int tierOf(Item item) {
        return item == null ? 0 : tierOfRarity(new ItemStack(item).getRarity());
    }

    public static int tierOfRarity(Rarity rarity) {
        if (rarity == Rarity.EPIC) {
            return 4;
        }
        if (rarity == Rarity.RARE) {
            return 3;
        }
        if (rarity == Rarity.UNCOMMON) {
            return 2;
        }
        return 1;
    }

    /** 按档次的加速比例（4 档及以上用 3 档的值）。 */
    public static double speedupOf(ItemStack stack) {
        return speedupOfTier(tierOf(stack));
    }

    public static double speedupOf(Item item) {
        return speedupOfTier(tierOf(item));
    }

    private static double speedupOfTier(int tier) {
        var config = HubSuite.fishing();
        if (config == null || config.bait == null) {
            return 0;
        }
        var rates = config.bait;
        return switch (tier) {
            case 1 -> rates.baitSpeedup;
            case 2 -> rates.compressedSpeedup;
            default -> rates.doubleCompressedSpeedup;
        };
    }

    /** 环境不匹配时的惩罚（正数）：速度 ×(1 - 惩罚)。 */
    public static double mismatchPenalty() {
        var config = HubSuite.fishing();
        return config == null || config.bait == null ? 0.40 : config.bait.familyMismatchPenalty;
    }

    /**
     * 一次"副手供饵"的解析结果。
     *
     * @param bait       这次会用/会扣的那种饵（没有饵 = null）
     * @param speedup    &gt;0 加速、&lt;0 减速（环境不匹配）、0 无影响
     * @param tier       该饵的档次
     * @param mismatched 是不是"环境不匹配"
     */
    public record Selection(Item bait, double speedup, int tier, boolean mismatched) {
        public static final Selection NONE = new Selection(null, 0.0, 0, false);

        public boolean present() {
            return bait != null;
        }
    }

    /**
     * 解析副手（散饵或渔桶）在当前环境下会用的饵。
     *
     * <p>先在同族里挑**最高档**（稀有度最高）—— 桶里混装时按这个顺序消耗；
     * 一个同族的都没有、但桶里有"另一族"的饵：
     * <ul>
     *   <li>副手是**渔桶** → 完全不用（不减速、不扣）；</li>
     *   <li>副手是**散饵** → 惩罚（减速），而且照样扣。</li>
     * </ul>
     */
    public static Selection select(Player player, boolean inLava) {
        if (player == null) {
            return Selection.NONE;
        }
        ItemStack off = player.getOffhandItem();
        if (off.isEmpty()) {
            return Selection.NONE;
        }
        /*
         * 渔桶：**只看栈顶那一种**（最后放进去的），不往下找。
         *   · 栈顶族匹配 → 按它自己的档次加速、钓上来扣 1 个；
         *   · 栈顶族不匹配 → **什么都不做**（不加速、不减速、不扣），
         *     即使下面压着正确族的饵也不去用（用户定的规则）。
         */
        if (off.getItem() instanceof BaitBucketItem) {
            Item top = BaitBucketItem.containedBait(off);
            if (top == null || isNetherBait(top) != inLava) {
                return Selection.NONE;
            }
            int tier = tierOf(top);
            return tier > 0 ? new Selection(top, speedupOfTier(tier), tier, false) : Selection.NONE;
        }

        /*
         * 散饵（副手直接放饵）：
         *   · 族匹配 → 加速；
         *   · 族不匹配 → **减速**（惩罚），而且照样扣 1 个。
         */
        if (!isBait(off)) {
            return Selection.NONE;
        }
        int tier = tierOf(off.getItem());
        if (tier <= 0) {
            return Selection.NONE;
        }
        if (isNetherBait(off) == inLava) {
            return new Selection(off.getItem(), speedupOfTier(tier), tier, false);
        }
        return new Selection(off.getItem(), -mismatchPenalty(), tier, true);
    }

    /** 副手上"实际生效的饵"：散饵就是它自己；渔桶就是桶里最高档的那种。 */
    public static Item effectiveBait(ItemStack offhand) {
        if (offhand.isEmpty()) {
            return null;
        }
        if (offhand.getItem() instanceof BaitBucketItem) {
            return BaitBucketItem.containedBait(offhand);
        }
        return isBait(offhand) ? offhand.getItem() : null;
    }

    /** 副手钓饵在当前环境下的加速比例（&gt;0 加速、&lt;0 减速、0 无影响）。 */
    public static double speedup(Player player, boolean inLava) {
        return select(player, inLava).speedup();
    }

    /** 钓上来一条：扣掉这次选中的那种饵（桶里装的就从桶里扣）。 */
    public static void consume(Player player, boolean inLava) {
        Selection sel = select(player, inLava);
        if (!sel.present()) {
            return;
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof BaitBucketItem) {
            if (sel.mismatched()) {
                return;
            }
            BaitBucketItem.remove(off, sel.bait(), 1);
            return;
        }
        off.shrink(1);
    }
}
