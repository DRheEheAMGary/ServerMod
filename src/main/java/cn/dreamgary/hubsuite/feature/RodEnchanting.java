package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.rod.RodData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * 青金石附魔（见 docs/钓竿系统方案.md 第 6 节）。
 *
 * <ul>
 *   <li>普通竿：钓上来的**装备**按**附魔台 15 级**等价附魔；</li>
 *   <li>青金石升级：按**附魔台 30 级**等价 + 有概率追加一个**宝藏型附魔**（**排除诅咒**）。</li>
 * </ul>
 *
 * <p><b>为什么要访问加宽</b>：原版把「按等级算附魔」的方法私有化在 {@code EnchantmentMenu} 里
 * （{@code getEnchantmentList}）。照着重写一遍附魔台算法很容易跟原版跑偏，
 * 所以用 {@code hubsuite.accesswidener} 把它开放出来直接调 —— 「等价附魔台」才是真的等价。
 */
public final class RodEnchanting {

    /** 普通竿：等价附魔台 15 级。 */
    public static final int NORMAL_LEVEL = 15;
    /** 青金石升级：等价附魔台 30 级。 */
    public static final int LAPIS_LEVEL = 30;
    /** 青金石升级追加宝藏附魔的概率（方案只写了「概率↑」，数值先定 25%，可调）。 */
    public static final float TREASURE_CHANCE = 0.25F;

    private RodEnchanting() {
    }

    /** 这根竿带青金石升级吗（= 附魔档次提升）。 */
    public static boolean boostsEnchant(Player player) {
        if (player == null) {
            return false;
        }
        RodData data = RodData.of(player.getMainHandItem());
        return data != null && data.boostsEnchant();
    }

    /** 钓上来的装备：按附魔台等价附魔（已经带附魔的不动）。 */
    public static ItemStack enchantCatch(ServerLevel level, ServerPlayer player, ItemStack stack, boolean lapis) {
        if (stack == null || stack.isEmpty() || !stack.isEnchantable() || stack.isEnchanted()) {
            return stack;
        }
        try {
            EnchantmentMenu menu = new EnchantmentMenu(0, player.getInventory(), ContainerLevelAccess.NULL);
            /*
             * 参数顺序反直觉，**别改**：反汇编原版这个方法确认过 ——
             *   getEnchantmentList(registries, stack, 种子偏移, 等级)
             * 第 3 个进 random.setSeed(...)，第 4 个才是拿去算附魔的等级。
             * 传反了不会报错，只是"按一个巨大的等级附魔"，表现为**时灵时不灵**（踩过一次）。
             */
            List<net.minecraft.world.item.enchantment.EnchantmentInstance> rolled = menu.getEnchantmentList(
                    level.registryAccess(), stack, level.getRandom().nextInt(),
                    lapis ? LAPIS_LEVEL : NORMAL_LEVEL);
            for (var instance : rolled) {
                stack.enchant(instance.enchantment(), instance.level());
            }
            if (lapis) {
                addTreasure(level, stack, level.getRandom().nextFloat());
            }
        } catch (Throwable t) {
            HubSuite.logger().debug("钓获附魔失败，原样给：{}", t.toString());
        }
        return stack;
    }

    /**
     * 追加一个宝藏型附魔（**排除两个诅咒**）。
     *
     * @param roll 抽签值；抽成参数是为了自检能测两端（0 必中、1 必不中）
     * @return 是否真的加上了
     */
    public static boolean addTreasure(ServerLevel level, ItemStack stack, float roll) {
        if (roll >= TREASURE_CHANCE) {
            return false;
        }
        List<Holder<Enchantment>> pool = treasurePool(level.registryAccess());
        if (pool.isEmpty()) {
            return false;
        }
        stack.enchant(pool.get(level.getRandom().nextInt(pool.size())), 1);
        return true;
    }

    /** 可用的宝藏附魔：{@code #minecraft:treasure} **减去** {@code #minecraft:curse}。 */
    public static List<Holder<Enchantment>> treasurePool(RegistryAccess registries) {
        var lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
        List<Holder<Enchantment>> pool = new ArrayList<>();
        for (Holder<Enchantment> holder : lookup.getOrThrow(EnchantmentTags.TREASURE)) {
            if (!holder.is(EnchantmentTags.CURSE)) {
                pool.add(holder);
            }
        }
        return pool;
    }
}
