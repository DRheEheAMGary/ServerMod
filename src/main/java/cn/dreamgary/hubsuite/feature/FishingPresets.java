package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 海岛渔获系统：配置结构与默认表。
 *
 * <p>设计取向（v0.4.0 用户要求）：
 * <ul>
 *   <li><b>按维度挂表</b>：只有配置过的维度才会被接管（默认只有海岛
 *       {@code hubsuite:skyblock_ocean}），其它维度**原样走原版**；</li>
 *   <li><b>分池 + 权重</b>：先抽池（鱼 / 宝物 / 垃圾），再抽条目 ——
 *       与原版 {@code gameplay/fishing} 的玩法一致，管理员一眼能看懂；</li>
 *   <li><b>稀有度分级</b>：common / uncommon / rare / legendary，
 *       稀有以上的钓获会给玩家一条提示（{@link FishingConfig#announceRare}）；</li>
 *   <li><b>按群系不同</b>：条目的 {@link Entry#biomes} 留空表示"任何群系"，
 *       填了就只在该群系出现 —— 暖海钓沙子、冷水洋钓砂砾、冻洋钓冰块就靠它。</li>
 * </ul>
 *
 * <p>掷骰逻辑在 {@link FishingLoot}（纯函数，可自检），
 * 游戏里由 {@code mixin/FishingHookLootMixin} 接上。
 */
public final class FishingPresets {

    private FishingPresets() {
    }

    /** 渔获总配置（config.json 的 fishing 段）。 */
    public static final class FishingConfig {
        /** 总开关。 */
        public boolean enabled = true;
        /** 维度 key（形如 {@code hubsuite:skyblock_ocean}）→ 该维度的渔获表。 */
        public Map<String, Table> tables = new LinkedHashMap<>();
        /** 钓到 rare / legendary 时是否给玩家一条提示。 */
        public boolean announceRare = true;

        public void normalize() {
            if (tables == null) {
                tables = new LinkedHashMap<>();
            }
            Map<String, Table> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, Table> e : tables.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) {
                    continue;
                }
                Table t = e.getValue();
                t.normalize();
                if (t.pools.isEmpty()) {
                    HubSuite.logger().warn("渔获表 '{}' 没有任何可用条目，已忽略。", e.getKey());
                    continue;
                }
                cleaned.put(e.getKey().trim(), t);
            }
            tables = cleaned;
        }
    }

    /** 一个维度的渔获表。 */
    public static final class Table {
        /** 显示名（命令/日志用）。 */
        public String displayName = "渔获";
        /** true = 完全替换原版渔获；false = 在原版基础上追加。 */
        public boolean replaceVanilla = true;
        /** 池子列表（按 {@link Pool#weight} 抽）。 */
        public List<Pool> pools = new ArrayList<>();

        public void normalize() {
            if (displayName == null || displayName.isBlank()) {
                displayName = "渔获";
            }
            if (pools == null) {
                pools = new ArrayList<>();
            }
            List<Pool> kept = new ArrayList<>();
            for (Pool p : pools) {
                if (p == null) {
                    continue;
                }
                p.normalize();
                if (!p.entries.isEmpty()) {
                    kept.add(p);
                }
            }
            pools = kept;
        }
    }

    /** 一个池子（鱼 / 宝物 / 垃圾…）。 */
    public static final class Pool {
        public String id = "fish";
        public double weight = 1.0;
        public List<Entry> entries = new ArrayList<>();

        public void normalize() {
            if (id == null || id.isBlank()) {
                id = "pool";
            }
            weight = Math.max(0.0, weight);
            if (entries == null) {
                entries = new ArrayList<>();
            }
            List<Entry> kept = new ArrayList<>();
            for (Entry e : entries) {
                if (e == null || e.item == null || e.item.isBlank()) {
                    continue;
                }
                e.normalize();
                kept.add(e);
            }
            entries = kept;
        }
    }

    /** 一条渔获。 */
    public static final class Entry {
        /** 物品 id，例如 {@code minecraft:nautilus_shell}。 */
        public String item = "minecraft:cod";
        public double weight = 1.0;
        /** 数量，支持 {@code "1"} 与 {@code "1-3"}。 */
        public String amount = "1";
        /** 稀有度：common / uncommon / rare / legendary。 */
        public String rarity = "common";
        /** 只在这些群系出现（path，不带命名空间）；留空 = 任何群系。 */
        public List<String> biomes = new ArrayList<>();

        public void normalize() {
            weight = Math.max(0.0, weight);
            if (amount == null || amount.isBlank()) {
                amount = "1";
            }
            if (rarity == null || rarity.isBlank()) {
                rarity = "common";
            }
            rarity = rarity.trim().toLowerCase(Locale.ROOT);
            if (biomes == null) {
                biomes = new ArrayList<>();
            }
            List<String> cleaned = new ArrayList<>();
            for (String b : biomes) {
                if (b != null && !b.isBlank()) {
                    cleaned.add(b.trim().toLowerCase(Locale.ROOT));
                }
            }
            biomes = cleaned;
        }

        /** 这个条目在该群系里能不能出现。 */
        public boolean eligible(String biomePath) {
            if (biomes.isEmpty()) {
                return true;   // 不限群系
            }
            return biomePath != null && biomes.contains(biomePath.toLowerCase(Locale.ROOT));
        }

        public boolean rare() {
            return "rare".equals(rarity) || "legendary".equals(rarity);
        }

        public int minAmount() {
            return parseAmount()[0];
        }

        public int maxAmount() {
            return parseAmount()[1];
        }

        /** 掷一次数量（含端点）。 */
        public int rollAmount(RandomSource random) {
            int min = minAmount();
            int max = maxAmount();
            return max <= min ? min : min + random.nextInt(max - min + 1);
        }

        private int[] parseAmount() {
            String text = amount == null ? "1" : amount.trim();
            int dash = text.indexOf('-');
            try {
                if (dash > 0) {
                    int min = Math.max(1, Integer.parseInt(text.substring(0, dash).trim()));
                    int max = Math.max(min, Integer.parseInt(text.substring(dash + 1).trim()));
                    return new int[]{min, max};
                }
                int one = Math.max(1, Integer.parseInt(text));
                return new int[]{one, one};
            } catch (NumberFormatException e) {
                return new int[]{1, 1};
            }
        }
    }

    // ------------------------------------------------------------------
    // 默认表
    // ------------------------------------------------------------------

    /**
     * 默认只给海岛维度一套表。
     *
     * <p>战利品追求"丰富"：**5 个池**（小鱼 / 大鱼 / 宝物 / 深海奇珍 / 垃圾），
     * 每个池十几条，并且鱼和垃圾都按群系分化 —— 暖海出热带鱼与沙子、
     * 冷水洋出鲑鱼与砂砾、冻洋出冰块与浮冰。
     */
    public static FishingConfig defaults() {
        FishingConfig config = new FishingConfig();

        Table ocean = new Table();
        ocean.displayName = "§b海岛渔获";
        ocean.replaceVanilla = true;

        // ── 小鱼：最常见的一档，各温度带都能钓到 ──
        ocean.pools.add(pool("small_fish", 40.0,
                entry("minecraft:cod", 40.0, "1-4", "common"),
                entry("minecraft:salmon", 26.0, "1-3", "common"),
                entry("minecraft:tropical_fish", 18.0, "1-2", "uncommon",
                        "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean"),
                entry("minecraft:pufferfish", 12.0, "1-2", "uncommon"),
                entry("minecraft:ink_sac", 10.0, "1-3", "common"),
                entry("minecraft:cod", 6.0, "1", "common",
                        "frozen_ocean", "deep_frozen_ocean"),
                entry("minecraft:kelp", 6.0, "1-2", "common")));

        // ── 大鱼 / 海产：量多，靠"海之眷顾"更容易出 ──
        ocean.pools.add(pool("big_catch", 18.0,
                entry("minecraft:cooked_cod", 20.0, "1-2", "common"),
                entry("minecraft:cooked_salmon", 18.0, "1-2", "common"),
                entry("minecraft:prismarine_shard", 16.0, "2-4", "common"),
                entry("minecraft:prismarine_crystals", 14.0, "1-3", "uncommon"),
                entry("minecraft:sponge", 8.0, "1", "rare"),
                entry("minecraft:sea_lantern", 6.0, "1", "rare"),
                // 乌龟鳞甲（老版本叫 scute，26.1 的物品 id 变了，这里用确定存在的）
                entry("minecraft:nautilus_shell", 6.0, "1-2", "rare"),
                entry("minecraft:glow_ink_sac", 6.0, "1-2", "uncommon")));

        // ── 宝物：原版"宝藏"那一档的加强版 ──
        ocean.pools.add(pool("treasure", 22.0,
                entry("minecraft:lily_pad", 14.0, "1-3", "common"),
                entry("minecraft:nautilus_shell", 14.0, "1", "rare"),
                entry("minecraft:name_tag", 10.0, "1", "rare"),
                entry("minecraft:saddle", 10.0, "1", "rare"),
                entry("minecraft:bow", 8.0, "1", "uncommon"),
                entry("minecraft:fishing_rod", 8.0, "1", "uncommon"),
                entry("minecraft:emerald", 8.0, "1-3", "uncommon"),
                entry("minecraft:iron_ingot", 8.0, "2-5", "common"),
                entry("minecraft:gold_ingot", 6.0, "1-3", "uncommon"),
                entry("minecraft:diamond", 4.0, "1", "rare"),
                entry("minecraft:golden_apple", 3.0, "1", "rare"),
                entry("minecraft:heart_of_the_sea", 1.0, "1", "legendary")));

        // ── 深海奇珍：稀有度最高的一档，幸运值加成也只加在这里 ──
        ocean.pools.add(pool("deep_rare", 8.0,
                entry("minecraft:diamond", 20.0, "1-2", "rare"),
                entry("minecraft:emerald_block", 12.0, "1", "rare"),
                entry("minecraft:golden_apple", 12.0, "1-2", "rare"),
                entry("minecraft:enchanted_golden_apple", 4.0, "1", "legendary"),
                entry("minecraft:trident", 4.0, "1", "legendary"),
                entry("minecraft:heart_of_the_sea", 6.0, "1", "legendary"),
                entry("minecraft:netherite_scrap", 2.0, "1", "legendary")));

        // ── 垃圾：按群系分化（暖海沙子 / 冷水洋砂砾 / 冻洋冰块）──
        ocean.pools.add(pool("junk", 12.0,
                entry("minecraft:sand", 55.0, "1-6", "common",
                        "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean"),
                entry("minecraft:gravel", 55.0, "1-5", "common",
                        "cold_ocean", "deep_cold_ocean"),
                entry("minecraft:ice", 50.0, "1-3", "common",
                        "frozen_ocean", "deep_frozen_ocean"),
                entry("minecraft:packed_ice", 10.0, "1", "uncommon",
                        "frozen_ocean", "deep_frozen_ocean"),
                entry("minecraft:kelp", 14.0, "1-4", "common"),
                entry("minecraft:seagrass", 12.0, "1-3", "common"),
                entry("minecraft:bone", 10.0, "1-2", "common"),
                entry("minecraft:string", 10.0, "1-2", "common"),
                entry("minecraft:stick", 10.0, "1-3", "common"),
                entry("minecraft:bowl", 8.0, "1-2", "common"),
                entry("minecraft:leather", 8.0, "1-2", "common"),
                entry("minecraft:rotten_flesh", 8.0, "1-2", "common"),
                entry("minecraft:clay_ball", 8.0, "1-4", "common"),
                entry("minecraft:bamboo", 6.0, "1-4", "common")));

        ocean.normalize();
        config.tables.put("hubsuite:skyblock_ocean", ocean);

        config.normalize();
        return config;
    }

    private static Pool pool(String id, double weight, Entry... entries) {
        Pool p = new Pool();
        p.id = id;
        p.weight = weight;
        for (Entry e : entries) {
            p.entries.add(e);
        }
        return p;
    }

    private static Entry entry(String item, double weight, String amount, String rarity,
                               String... biomes) {
        Entry e = new Entry();
        e.item = item;
        e.weight = weight;
        e.amount = amount;
        e.rarity = rarity;
        for (String b : biomes) {
            e.biomes.add(b);
        }
        e.normalize();
        return e;
    }
}
