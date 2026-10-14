package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.feature.item.BaitBucketItem;
import cn.dreamgary.hubsuite.feature.item.HubSuiteItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;

/**
 * 副手钓饵。
 *
 * <p>规则（用户定）：
 * <ul>
 *   <li><b>普通钓饵</b>（含压缩档）只在**水里**生效；<b>地狱钓饵</b>（含压缩档）只在**岩浆里**生效；</li>
 *   <li>加速：普通 +10%、压缩 +25%、二重压缩 +45%（等待时间按比例缩短）；</li>
 *   <li><b>钓上来的时候扣一个</b>；</li>
 *   <li>渔桶可以**混装**不同钓饵，消耗顺序按档次：<b>二重压缩 → 压缩 → 普通</b>；</li>
 *   <li><b>散饵用错环境 = 减速 + 照样扣</b>：拿普通饵去岩浆里钓（或反过来），
 *       速度 ×(1 - {@code bait.familyMismatchPenalty})，压缩不压缩一个价；</li>
 *   <li><b>渔桶里全是错误族的饵 = 完全不用</b>：不减速、也不扣（桶护饵）—— 见 {@link #select}。</li>
 * </ul>
 */
public final class FishingBait {

    private FishingBait() {
    }

    /** 档次：二重压缩 3 &gt; 压缩 2 &gt; 普通 1；不是钓饵 = 0。 */
    public static int tierOf(Item item) {
        if (item == HubSuiteItems.DOUBLE_COMPRESSED_BAIT
                || item == HubSuiteItems.DOUBLE_COMPRESSED_NETHER_BAIT) {
            return 3;
        }
        if (item == HubSuiteItems.COMPRESSED_BAIT || item == HubSuiteItems.COMPRESSED_NETHER_BAIT) {
            return 2;
        }
        if (item == HubSuiteItems.BAIT || item == HubSuiteItems.NETHER_BAIT) {
            return 1;
        }
        return 0;
    }

    /**
     * 这个物品是不是钓饵（含压缩档）。
     *
     * <p>按**物品本身**判断，不看配置里的加速值 —— 配置把加速调成 0 不该让桶"不认饵"。
     */
    public static boolean isBait(Item item) {
        return tierOf(item) > 0;
    }

    public static boolean isNetherBait(Item item) {
        return item == HubSuiteItems.NETHER_BAIT
                || item == HubSuiteItems.COMPRESSED_NETHER_BAIT
                || item == HubSuiteItems.DOUBLE_COMPRESSED_NETHER_BAIT;
    }

    public static double speedupOf(Item item) {
        var config = HubSuite.fishing();
        if (config == null || config.bait == null) {
            return 0;
        }
        var rates = config.bait;
        return switch (tierOf(item)) {
            case 1 -> rates.baitSpeedup;
            case 2 -> rates.compressedSpeedup;
            case 3 -> rates.doubleCompressedSpeedup;
            default -> 0;
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
     * @param bait        这次会用/会扣的那种饵（没有饵 = null）
     * @param speedup     &gt;0 加速、&lt;0 减速（环境不匹配）、0 无影响
     * @param tier        该饵的档次
     * @param mismatched  是不是"环境不匹配"（普通饵下水/地狱饵下岩浆的反面）
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
     * <p>顺序：
     * <ol>
     *   <li>先在同族里挑**最高档**（二重压缩 → 压缩 → 普通）—— 桶里混装时按这个顺序消耗；</li>
     *   <li>一个同族的都没有、但桶里有"另一族"的饵 → 返回 {@code mismatched}，
     *       速度取负的惩罚值；**照样消耗**（用户定的：乱用饵要付代价）。</li>
     * </ol>
     *
     * @param inLava 浮漂此刻是不是泡在岩浆里
     */
    public static Selection select(Player player, boolean inLava) {
        if (player == null) {
            return Selection.NONE;
        }
        ItemStack off = player.getOffhandItem();
        if (off.isEmpty()) {
            return Selection.NONE;
        }
        Map<Item, Integer> pool;
        if (off.getItem() instanceof BaitBucketItem) {
            pool = BaitBucketItem.contents(off);
        } else if (isBait(off.getItem())) {
            pool = Map.of(off.getItem(), off.getCount());
        } else {
            return Selection.NONE;
        }

        // 1) 同族最高档（桶里已经按档次排好，第一个命中即最高档）
        for (var e : pool.entrySet()) {
            if (e.getValue() <= 0 || isNetherBait(e.getKey()) != inLava) {
                continue;
            }
            int tier = tierOf(e.getKey());
            if (tier > 0) {
                return new Selection(e.getKey(), speedupOf(e.getKey()), tier, false);
            }
        }

        // 2) 只有另一族的饵：
        //    · 副手是**渔桶** → 桶里那些错误族的饵**完全不用**（不减速、也不扣，用户定的）；
        //    · 副手是**散饵** → 惩罚（减速），而且照样扣（乱用要付代价）。
        if (off.getItem() instanceof BaitBucketItem) {
            return Selection.NONE;
        }
        for (var e : pool.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            int tier = tierOf(e.getKey());
            if (tier > 0) {
                return new Selection(e.getKey(), -mismatchPenalty(), tier, true);
            }
        }
        return Selection.NONE;
    }

    /**
     * 副手上"实际生效的饵"：散饵就是它自己；渔桶就是桶里**最高档**的那种（空桶 → null）。
     */
    public static Item effectiveBait(ItemStack offhand) {
        if (offhand.isEmpty()) {
            return null;
        }
        if (offhand.getItem() instanceof BaitBucketItem) {
            return BaitBucketItem.containedBait(offhand);
        }
        return isBait(offhand.getItem()) ? offhand.getItem() : null;
    }

    /**
     * 副手钓饵在当前环境下的加速比例。
     *
     * <p>&gt;0 = 加速；&lt;0 = 环境不匹配的减速（等待时间变长）；没饵 / 空桶 = 0。
     *
     * @param inLava 浮漂此刻是不是泡在岩浆里
     */
    public static double speedup(Player player, boolean inLava) {
        return select(player, inLava).speedup();
    }

    /** 钓上来一条：扣掉**这次选中的那种**饵（桶里装的就从桶里扣）。 */
    public static void consume(Player player, boolean inLava) {
        Selection sel = select(player, inLava);
        if (!sel.present()) {
            return;
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof BaitBucketItem) {
            // 渔桶"护饵"：错误族的饵 select 已经不返回了（这里是双保险）
            if (sel.mismatched()) {
                return;
            }
            BaitBucketItem.remove(off, sel.bait(), 1);
            return;
        }
        off.shrink(1);
    }
}
