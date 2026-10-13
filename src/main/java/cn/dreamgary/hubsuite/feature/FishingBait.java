package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.feature.item.HubSuiteItems;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 副手钓饵。
 *
 * <p>规则（用户定）：
 * <ul>
 *   <li><b>普通钓饵</b>（含压缩档）只在**水里**生效；</li>
 *   <li><b>地狱钓饵</b>（含压缩档）只在**岩浆里**生效；</li>
 *   <li>加速：普通 +10%、压缩 +25%、二重压缩 +45%（等待时间按比例缩短）；</li>
 *   <li>**钓上来的时候扣一个**。</li>
 * </ul>
 */
public final class FishingBait {

    private FishingBait() {
    }

    /** 这个物品是不是钓饵（含压缩档）。 */
    public static boolean isBait(Item item) {
        return speedupOf(item) > 0;
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
        if (item == HubSuiteItems.BAIT || item == HubSuiteItems.NETHER_BAIT) {
            return rates.baitSpeedup;
        }
        if (item == HubSuiteItems.COMPRESSED_BAIT || item == HubSuiteItems.COMPRESSED_NETHER_BAIT) {
            return rates.compressedSpeedup;
        }
        if (item == HubSuiteItems.DOUBLE_COMPRESSED_BAIT
                || item == HubSuiteItems.DOUBLE_COMPRESSED_NETHER_BAIT) {
            return rates.doubleCompressedSpeedup;
        }
        return 0;
    }

    /**
     * 副手钓饵在当前环境下的加速比例；环境不匹配 / 没钓饵返回 0。
     *
     * @param inLava 浮漂此刻是不是泡在岩浆里
     */
    public static double speedup(Player player, boolean inLava) {
        if (player == null) {
            return 0;
        }
        ItemStack off = player.getOffhandItem();
        if (off.isEmpty()) {
            return 0;
        }
        Item item = off.getItem();
        double value = speedupOf(item);
        if (value <= 0) {
            return 0;
        }
        return isNetherBait(item) == inLava ? value : 0;
    }

    /** 钓上来一条：扣掉一个生效的副手钓饵。 */
    public static void consume(Player player, boolean inLava) {
        if (player == null || speedup(player, inLava) <= 0) {
            return;
        }
        player.getOffhandItem().shrink(1);
    }
}
