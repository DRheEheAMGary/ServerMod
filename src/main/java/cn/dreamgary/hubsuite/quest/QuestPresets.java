package cn.dreamgary.hubsuite.quest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置任务表。
 *
 * <p>设计取向：任务围绕**空岛前期最缺的东西**（木头、石头、食物、铁、钻石），
 * 并覆盖"经典空岛"和"海岛"两条路线。全部写进 config.json，
 * 管理员可以随意增删改。
 */
public final class QuestPresets {

    private QuestPresets() {
    }

    /** 任务系统的总配置。 */
    public static final class QuestConfig {
        public boolean enabled = true;
        /** 每种类型的任务列表。 */
        public List<Quest> quests = new ArrayList<>();
        /** 完成任务时是否在聊天里播报。 */
        public boolean announceOnComplete = true;
        /** 每日任务的"一天"从几点开始（0-23），默认凌晨 4 点（与原版"睡一觉算新一天"更接近）。 */
        public int dailyResetHour = 4;

        public void normalize() {
            if (quests == null) {
                quests = new ArrayList<>();
            }
            Map<String, Quest> byId = new LinkedHashMap<>();
            for (Quest quest : quests) {
                if (quest == null) {
                    continue;
                }
                quest.compile();
                if (!quest.valid()) {
                    continue;
                }
                quest.id = quest.id.trim().toLowerCase(java.util.Locale.ROOT);
                if (quest.name == null || quest.name.isBlank()) {
                    quest.name = quest.id;
                }
                if (quest.icon == null || quest.icon.isBlank()) {
                    quest.icon = "minecraft:paper";
                }
                if (quest.rewards == null) {
                    quest.rewards = new ArrayList<>();
                }
                byId.put(quest.id, quest);
            }
            quests = new ArrayList<>(byId.values());
            dailyResetHour = Math.max(0, Math.min(23, dailyResetHour));
        }

        public Quest byId(String id) {
            if (id == null) {
                return null;
            }
            for (Quest quest : quests) {
                if (quest.id.equalsIgnoreCase(id)) {
                    return quest;
                }
            }
            return null;
        }

        public List<Quest> ofKind(Quest.Kind kind) {
            List<Quest> out = new ArrayList<>();
            for (Quest quest : quests) {
                if (quest.kind == kind) {
                    out.add(quest);
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------

    public static QuestConfig defaults() {
        QuestConfig config = new QuestConfig();
        List<Quest> quests = new ArrayList<>();

        // ── 里程碑：开局 ──
        quests.add(milestone("first_log", "§a第一块木头", "砍一棵树，空岛从这一步开始",
                "minecraft:oak_log", "break:minecraft:oak_log", 1,
                "minecraft:oak_sapling*2", "minecraft:bread*2"));

        quests.add(milestone("first_craft", "§a工作台", "做出一个工作台",
                "minecraft:crafting_table", "have:minecraft:crafting_table", 1,
                "minecraft:bread*4"));

        quests.add(milestone("stone_64", "§7石匠入门", "挖 64 个石头",
                "minecraft:stone", "break:minecraft:stone", 64,
                "minecraft:stone_pickaxe*1", "minecraft:bread*4"));

        quests.add(milestone("cobble_128", "§7圆石储备", "攒 128 个圆石",
                "minecraft:cobblestone", "have:minecraft:cobblestone", 128,
                "minecraft:iron_ingot*4", "minecraft:torch*16"));

        // ── 里程碑：农业与食物 ──
        quests.add(milestone("wheat_16", "§e小麦丰收", "收获 16 个小麦",
                "minecraft:wheat", "have:minecraft:wheat", 16,
                "minecraft:bread*8", "minecraft:bone_meal*8"));

        quests.add(milestone("bread_16", "§e烤面包", "攒 16 个面包",
                "minecraft:bread", "have:minecraft:bread", 16,
                "minecraft:cooked_beef*8"));

        // ── 里程碑：畜牧 ──
        quests.add(milestone("kill_zombie_10", "§c夜里的东西", "击杀 10 只僵尸",
                "minecraft:rotten_flesh", "kill:minecraft:zombie", 10,
                "minecraft:iron_ingot*2", "minecraft:bread*4"));

        quests.add(milestone("kill_skeleton_10", "§f白骨", "击杀 10 只骷髅",
                "minecraft:bone", "kill:minecraft:skeleton", 10,
                "minecraft:arrow*16", "minecraft:bone_meal*8"));

        // ── 里程碑：矿物 ──
        quests.add(milestone("iron_32", "§f铁器时代", "攒 32 个铁锭",
                "minecraft:iron_ingot", "have:minecraft:iron_ingot", 32,
                "minecraft:diamond*2", "minecraft:anvil*1"));

        quests.add(milestone("first_diamond", "§b第一颗钻石", "挖到一颗钻石",
                "minecraft:diamond", "have:minecraft:diamond", 1,
                "minecraft:diamond_pickaxe*1"));

        quests.add(milestone("diamond_16", "§b钻石储备", "攒 16 颗钻石",
                "minecraft:diamond_block", "have:minecraft:diamond", 16,
                "minecraft:enchanted_golden_apple*1", "minecraft:diamond_block*2"));

        // ── 里程碑：海岛路线 ──
        quests.add(milestone("ocean_fish_16", "§b渔猎", "钓到 16 条鱼",
                "minecraft:fishing_rod", "have:minecraft:cod", 16,
                "minecraft:cooked_cod*8", "minecraft:iron_ingot*4"));

        quests.add(milestone("ocean_sail", "§b出海", "做一条船",
                "minecraft:oak_boat", "have:minecraft:oak_boat", 1,
                "minecraft:map*1", "minecraft:compass*1"));

        // ── 里程碑：探索 ──
        quests.add(milestone("reach_y120", "§d登高", "到达 Y=120",
                "minecraft:ladder", "reach_y:120", 1,
                "minecraft:golden_apple*2"));

        // ── 可重复交付 ──
        quests.add(deliver("deliver_cobble", "§7收石头", "交 64 个圆石换一颗钻石",
                "minecraft:diamond", "minecraft:cobblestone", 64,
                "minecraft:diamond*1"));

        quests.add(deliver("deliver_wheat", "§e收小麦", "交 32 个小麦换铁锭",
                "minecraft:iron_ingot", "minecraft:wheat", 32,
                "minecraft:iron_ingot*4", "minecraft:bread*4"));

        quests.add(deliver("deliver_log", "§a收木头", "交 64 个原木换骨粉",
                "minecraft:bone_meal", "minecraft:oak_log", 64,
                "minecraft:bone_meal*16", "minecraft:oak_sapling*4"));

        // ── 每日任务 ──
        quests.add(daily("daily_mine_128", "§7每日·采矿", "今天挖 128 个石头",
                "minecraft:stone_pickaxe", "break:minecraft:stone", 128,
                "minecraft:iron_ingot*2", "minecraft:torch*16"));

        quests.add(daily("daily_kill_20", "§c每日·清怪", "今天击杀 20 只怪物",
                "minecraft:iron_sword", "kill:minecraft:zombie", 20,
                "minecraft:iron_ingot*3", "minecraft:bread*8"));

        quests.add(daily("daily_fish_8", "§b每日·钓鱼", "今天钓 8 条鱼",
                "minecraft:fishing_rod", "have:minecraft:cod", 8,
                "minecraft:cooked_cod*8"));

        config.quests = quests;
        config.normalize();
        return config;
    }

    // ------------------------------------------------------------------

    private static Quest milestone(String id, String name, String description, String icon,
                                   String goal, int amount, String... rewards) {
        return build(Quest.Kind.MILESTONE, id, name, description, icon, goal, amount, rewards);
    }

    private static Quest deliver(String id, String name, String description, String icon,
                                 String itemId, int amount, String... rewards) {
        return build(Quest.Kind.DELIVER, id, name, description, icon,
                "deliver:" + itemId, amount, rewards);
    }

    private static Quest daily(String id, String name, String description, String icon,
                               String goal, int amount, String... rewards) {
        return build(Quest.Kind.DAILY, id, name, description, icon, goal, amount, rewards);
    }

    private static Quest build(Quest.Kind kind, String id, String name, String description,
                               String icon, String goal, int amount, String... rewards) {
        Quest quest = new Quest();
        quest.kind = kind;
        quest.id = id;
        quest.name = name;
        quest.description = description;
        quest.icon = icon;
        quest.goal = goal;
        quest.amount = amount;
        quest.rewards = new ArrayList<>(List.of(rewards));
        quest.compile();
        return quest;
    }
}
