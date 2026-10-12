package cn.dreamgary.hubsuite.feature;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 渔获掷骰（纯函数）。
 *
 * <p>刻意跟 Mixin 分开：这样自检可以**不碰实体、不加载区块**地验证
 * 权重、群系过滤、数量区间与稀有度，符合本项目"自检不许做重活"的约束。
 */
public final class FishingLoot {

    private FishingLoot() {
    }

    /** 一次钓上来的东西 + 它来自哪条配置（用来判稀有度、决定要不要提示）。 */
    public record Catch(ItemStack stack, FishingPresets.Entry entry) {
    }

    /** 该维度有没有被接管的渔获表；没有返回 null（调用方保持原版行为）。 */
    public static FishingPresets.Table tableFor(FishingPresets.FishingConfig config,
                                                ResourceKey<Level> dimension) {
        if (config == null || !config.enabled || dimension == null || config.tables == null) {
            return null;
        }
        return config.tables.get(dimension.identifier().toString());
    }

    /**
     * 掷一次渔获。
     *
     * @param biomePath 钓钩所在群系的 path（不带命名空间），可为 null
     * @param luck      玩家的幸运值（海之眷顾/幸运），只提升稀有以上条目的权重
     * @return 依次要发给玩家的物品；没有可用条目时返回空列表
     */
    public static List<Catch> roll(FishingPresets.Table table, String biomePath,
                                   RandomSource random, int luck) {
        if (table == null || table.pools == null || table.pools.isEmpty()) {
            return List.of();
        }
        FishingPresets.Pool pool = pickPool(table, random);
        if (pool == null) {
            return List.of();
        }
        FishingPresets.Entry entry = pickEntry(pool, biomePath, random, luck);
        if (entry == null) {
            return List.of();
        }
        Item item = itemOf(entry.item);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return List.of();
        }
        int amount = entry.rollAmount(random);
        int max = Math.max(1, item.getDefaultMaxStackSize());
        List<Catch> out = new ArrayList<>();
        int left = amount;
        while (left > 0) {
            int take = Math.min(left, max);
            out.add(new Catch(new ItemStack(item, take), entry));
            left -= take;
        }
        return out;
    }

    private static FishingPresets.Pool pickPool(FishingPresets.Table table, RandomSource random) {
        double total = 0.0;
        for (FishingPresets.Pool p : table.pools) {
            if (p.weight > 0.0 && !p.entries.isEmpty()) {
                total += p.weight;
            }
        }
        if (total <= 0.0) {
            return null;
        }
        double r = random.nextDouble() * total;
        for (FishingPresets.Pool p : table.pools) {
            if (p.weight <= 0.0 || p.entries.isEmpty()) {
                continue;
            }
            r -= p.weight;
            if (r <= 0.0) {
                return p;
            }
        }
        return table.pools.get(table.pools.size() - 1);
    }

    private static FishingPresets.Entry pickEntry(FishingPresets.Pool pool, String biomePath,
                                                  RandomSource random, int luck) {
        double total = 0.0;
        for (FishingPresets.Entry e : pool.entries) {
            if (e.eligible(biomePath)) {
                total += weightOf(e, luck);
            }
        }
        if (total <= 0.0) {
            return null;
        }
        double r = random.nextDouble() * total;
        FishingPresets.Entry last = null;
        for (FishingPresets.Entry e : pool.entries) {
            if (!e.eligible(biomePath)) {
                continue;
            }
            last = e;
            r -= weightOf(e, luck);
            if (r <= 0.0) {
                return e;
            }
        }
        return last;
    }

    /** 幸运值只加稀有以上的权重 —— 相当于原版"海之眷顾"偏向宝物，但幅度可控。 */
    private static double weightOf(FishingPresets.Entry entry, int luck) {
        double w = entry.weight;
        if (luck > 0 && entry.rare()) {
            w *= 1.0 + 0.15 * luck;
        }
        return w;
    }

    private static Item itemOf(String id) {
        try {
            Identifier key = Identifier.tryParse(id);
            if (key == null) {
                return null;
            }
            return BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
