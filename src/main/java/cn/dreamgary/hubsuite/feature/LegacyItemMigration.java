package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.feature.item.HubSuiteItems;
import cn.dreamgary.hubsuite.rod.RodData;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 旧物品换算：0.5 的 {@code hubsuite:lava_rod} → 0.6 的「原版钓竿 + 黑曜石钩」。
 *
 * <p>0.6 起能不能钓岩浆**由钩的材质决定**（黑曜石 / 下界合金），不再有专用竿。
 * 但那个物品不能直接删（老存档里的旧竿会变成空气），所以它留成
 * {@link cn.dreamgary.hubsuite.feature.item.LegacyLavaRodItem 遗留别名}，
 * 玩家进服时在这里把背包里的旧竿**就地换掉**：
 * <ul>
 *   <li>附魔与自定义名字跟着走（{@code transmuteCopy}）；</li>
 *   <li>**磨损比例照搬**：旧竿快坏了，新竿也快坏 —— 既不白送满耐久，也不会一换就坏；</li>
 *   <li>换完立刻 {@code broadcastFullState()}，客户端看到的才是新竿。</li>
 * </ul>
 */
public final class LegacyItemMigration {

    private LegacyItemMigration() {
    }

    public static void register() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                migrate(handler.getPlayer());
            } catch (Throwable t) {
                HubSuite.logger().debug("旧物品换算失败（不影响进服）：{}", t.toString());
            }
        });
    }

    /** 把背包里的旧熔岩钓竿全部换掉，返回换了几根。 */
    public static int migrate(ServerPlayer player) {
        var inventory = player.getInventory();
        int converted = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (isLegacyLavaRod(inventory.getItem(i))) {
                inventory.setItem(i, convert(inventory.getItem(i)));
                converted++;
            }
        }
        if (converted > 0) {
            player.inventoryMenu.broadcastFullState();
            player.sendSystemMessage(Component.literal(
                    "\u00A77你有 " + converted + " 根旧熔岩钓竿，已经换成「挂黑曜石钩的钓竿」——"
                            + "现在能不能钓岩浆看**钩**，不看竿。"));
        }
        return converted;
    }

    /** 是不是那根遗留的旧竿。 */
    public static boolean isLegacyLavaRod(ItemStack stack) {
        return stack != null && !stack.isEmpty() && HubSuiteItems.LAVA_ROD != null
                && stack.is(HubSuiteItems.LAVA_ROD);
    }

    /** 单件换算（纯函数，自检直接测）。 */
    public static ItemStack convert(ItemStack legacy) {
        ItemStack rod = legacy.transmuteCopy(Items.FISHING_ROD, legacy.getCount());
        RodData base = RodData.defaults().withParts("wood", "string", "obsidian");

        // 旧竿的原版耐久按**比例**搬过来（旧竿 MAX_DAMAGE=256，新竿是按我们的上限算的）
        Integer max = legacy.get(DataComponents.MAX_DAMAGE);
        Integer damage = legacy.get(DataComponents.DAMAGE);
        float kept = max == null || max <= 0
                ? 1.0F
                : 1.0F - Math.min(1.0F, (damage == null ? 0 : damage) / (float) max);
        int durability = Math.max(10, Math.round(kept * base.maxDurability()));

        RodData data = new RodData(base.rod(), base.line(), base.hook(), base.upgrade(),
                durability, base.maxDurability());
        data.writeTo(rod);
        data.applyVanillaBar(rod);
        return rod;
    }
}
