package cn.dreamgary.hubsuite.quest;

import cn.dreamgary.hubsuite.HubSuite;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务进度：计数、完成状态、发奖。
 *
 * <p>存储按玩家 UUID 分一条记录，写在
 * {@code config/hubsuite/skyblock/quests.json}。
 *
 * <p>三种任务共用一个计数器表：
 * <ul>
 *   <li>里程碑用 {@code counters} + {@code done}；</li>
 *   <li>可重复交付用 {@code counters}（每次交付清零）不给 {@code done} 锁死；</li>
 *   <li>每日任务用 {@code dailyCounters} + {@code dailyDone}，并记录
 *       {@code dailyDate}；日期一变就整体重置。</li>
 * </ul>
 */
public final class QuestManager {

    /** 一个玩家的任务进度。 */
    public static final class Progress {
        /** 目标 → 累计数量（里程碑 + 交付）。 */
        public Map<String, Integer> counters = new LinkedHashMap<>();
        /** 已完成的里程碑 id。 */
        public Set<String> done = new LinkedHashSet<>();
        /** 当前每日任务的日期（ISO，形如 2026-10-05）。 */
        public String dailyDate = "";
        public Map<String, Integer> dailyCounters = new LinkedHashMap<>();
        public Set<String> dailyDone = new LinkedHashSet<>();
    }

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting().disableHtmlEscaping().create();
    private static final TypeToken<Map<String, Progress>> MAP_TYPE = new TypeToken<>() {
    };

    private final QuestPresets.QuestConfig config;
    private final Path storeFile;
    private final Map<String, Progress> players = new LinkedHashMap<>();

    public QuestManager(QuestPresets.QuestConfig config) {
        this.config = config;
        this.storeFile = FabricLoader.getInstance().getConfigDir()
                .resolve(HubSuite.MOD_ID).resolve("skyblock").resolve("quests.json");
        load();
    }

    public QuestPresets.QuestConfig config() {
        return config;
    }

    public Path storeFile() {
        return storeFile;
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    private void load() {
        try {
            if (!Files.exists(storeFile)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
                Map<String, Progress> loaded = GSON.fromJson(reader, MAP_TYPE.getType());
                if (loaded != null) {
                    players.putAll(loaded);
                }
            }
            // 反序列化可能带来 null 字段，统一补齐（否则后面到处 NPE）
            players.values().forEach(this::repair);
        } catch (Throwable t) {
            HubSuite.logger().error("读取任务进度失败（将从头开始）：{}", storeFile, t);
        }
    }

    private void repair(Progress progress) {
        if (progress.counters == null) {
            progress.counters = new LinkedHashMap<>();
        }
        if (progress.done == null) {
            progress.done = new LinkedHashSet<>();
        }
        if (progress.dailyDate == null) {
            progress.dailyDate = "";
        }
        if (progress.dailyCounters == null) {
            progress.dailyCounters = new LinkedHashMap<>();
        }
        if (progress.dailyDone == null) {
            progress.dailyDone = new LinkedHashSet<>();
        }
    }

    public void save() {
        try {
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(players, writer);
            }
            try {
                Files.move(tmp, storeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException atomicUnsupported) {
                Files.move(tmp, storeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            HubSuite.logger().error("保存任务进度失败：{}", storeFile, t);
        }
    }

    private Progress progressOf(ServerPlayer player) {
        Progress progress = players.computeIfAbsent(player.getUUID().toString(), k -> new Progress());
        repair(progress);
        ensureDailyFresh(progress);
        return progress;
    }

    // ------------------------------------------------------------------
    // 每日重置
    // ------------------------------------------------------------------

    /** 当前"任务日"的标识（按配置的 dailyResetHour 切分，而不是简单按自然日）。 */
    public String currentDay() {
        LocalDateTime now = LocalDateTime.now();
        if (now.getHour() < config.dailyResetHour) {
            now = now.minusDays(1);
        }
        return now.toLocalDate().toString();
    }

    private void ensureDailyFresh(Progress progress) {
        String today = currentDay();
        if (today.equals(progress.dailyDate)) {
            return;
        }
        progress.dailyDate = today;
        progress.dailyCounters.clear();
        progress.dailyDone.clear();
        HubSuite.logger().debug("任务日切换到 {}，每日任务已重置", today);
    }

    // ------------------------------------------------------------------
    // 进度推进
    // ------------------------------------------------------------------

    /**
     * 推进进度（由游戏事件调用）。
     *
     * @param goalType 目标类型
     * @param arg      目标参数（方块 id / 生物 id / 物品 id）
     * @param delta    增量
     */
    public void advance(ServerPlayer player, Quest.GoalType goalType, String arg, int delta) {
        if (!config.enabled || delta <= 0 || arg == null) {
            return;
        }
        try {
            Progress progress = progressOf(player);
            String key = goalType.name().toLowerCase(java.util.Locale.ROOT) + ":" + arg;

            boolean changed = false;
            for (Quest quest : config.quests) {
                if (quest.goalType() != goalType || !arg.equalsIgnoreCase(quest.goalArg())) {
                    continue;
                }
                if (quest.kind == Quest.Kind.DAILY) {
                    int before = progress.dailyCounters.getOrDefault(key, 0);
                    int after = Math.min(quest.amount, before + delta);
                    if (after != before) {
                        progress.dailyCounters.put(key, after);
                        changed = true;
                    }
                } else if (quest.kind == Quest.Kind.MILESTONE) {
                    if (progress.done.contains(quest.id)) {
                        continue;
                    }
                    int before = progress.counters.getOrDefault(key, 0);
                    int after = Math.min(quest.amount, before + delta);
                    if (after != before) {
                        progress.counters.put(key, after);
                        changed = true;
                    }
                }
            }
            if (changed) {
                save();
            }
        } catch (Throwable t) {
            HubSuite.logger().error("推进任务进度失败（{}）", goalType, t);
        }
    }

    /**
     * 对"当前持有量"类目标做同步（{@code have:} 用的是背包现有数量）。
     *
     * <p>比"监听获得物品"简单且幂等：玩家丢掉东西进度也会跟着退，
     * 符合"攒够 N 个"的直觉。
     */
    public void syncHaveGoals(ServerPlayer player) {
        if (!config.enabled) {
            return;
        }
        try {
            Progress progress = progressOf(player);
            boolean changed = false;
            for (Quest quest : config.quests) {
                if (quest.goalType() != Quest.GoalType.HAVE) {
                    continue;
                }
                Item item = itemOf(quest.goalArg());
                if (item == null || item == Items.AIR) {
                    continue;
                }
                int have = player.getInventory().countItem(item);
                String key = quest.counterKey();

                if (quest.kind == Quest.Kind.DAILY) {
                    int after = Math.min(quest.amount, have);
                    if (progress.dailyCounters.getOrDefault(key, 0) != after) {
                        progress.dailyCounters.put(key, after);
                        changed = true;
                    }
                } else if (quest.kind == Quest.Kind.MILESTONE
                        && !progress.done.contains(quest.id)) {
                    int after = Math.min(quest.amount, have);
                    if (progress.counters.getOrDefault(key, 0) != after) {
                        progress.counters.put(key, after);
                        changed = true;
                    }
                }
            }
            if (changed) {
                save();
            }
        } catch (Throwable t) {
            HubSuite.logger().error("同步持有量任务失败", t);
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /** 任务当前进度（0..amount）。 */
    public int progressOf(ServerPlayer player, Quest quest) {
        Progress progress = progressOf(player);
        String key = quest.counterKey();
        return switch (quest.kind) {
            case DAILY -> Math.min(quest.amount, progress.dailyCounters.getOrDefault(key, 0));
            default -> Math.min(quest.amount, progress.counters.getOrDefault(key, 0));
        };
    }

    /** 任务是否已完成（可领取）。 */
    public boolean isComplete(ServerPlayer player, Quest quest) {
        Progress progress = progressOf(player);
        return switch (quest.kind) {
            case DAILY -> progress.dailyDone.contains(quest.id)
                    || progress.dailyCounters.getOrDefault(quest.counterKey(), 0) >= quest.amount;
            case DELIVER -> canDeliver(player, quest);
            case MILESTONE -> progress.done.contains(quest.id)
                    || progress.counters.getOrDefault(quest.counterKey(), 0) >= quest.amount;
        };
    }

    /** 里程碑是否已经领过奖（领过的显示为"已完成"）。 */
    public boolean isClaimed(ServerPlayer player, Quest quest) {
        Progress progress = progressOf(player);
        return switch (quest.kind) {
            case DAILY -> progress.dailyDone.contains(quest.id);
            case MILESTONE -> progress.done.contains(quest.id);
            case DELIVER -> false;   // 可重复，永远不算"已领完"
        };
    }

    /** 交付类任务：背包里够不够。 */
    public boolean canDeliver(ServerPlayer player, Quest quest) {
        Item item = itemOf(quest.goalArg());
        return item != null && item != Items.AIR
                && player.getInventory().countItem(item) >= quest.amount;
    }

    // ------------------------------------------------------------------
    // 领奖 / 交付
    // ------------------------------------------------------------------

    /**
     * 领取奖励（里程碑）或执行交付（可重复）。
     *
     * @return 结果描述；失败时说明原因
     */
    public String claim(ServerPlayer player, Quest quest) {
        if (!config.enabled) {
            return "§c任务系统未启用。";
        }
        Progress progress = progressOf(player);

        if (quest.kind == Quest.Kind.DELIVER) {
            Item item = itemOf(quest.goalArg());
            if (item == null || item == Items.AIR) {
                return "§c任务配置有误：认不出物品 " + quest.goalArg();
            }
            if (player.getInventory().countItem(item) < quest.amount) {
                return "§c材料不够，还需要 "
                        + (quest.amount - player.getInventory().countItem(item)) + " 个。";
            }
            consume(player, item, quest.amount);
        } else if (isClaimed(player, quest)) {
            return "§7这个任务已经完成了。";
        } else if (progressOf(player, quest) < quest.amount) {
            return "§c还没完成（" + progressOf(player, quest) + "/" + quest.amount + "）。";
        }

        List<ItemStack> given = new ArrayList<>();
        for (String reward : quest.rewards) {
            ItemStack stack = parseReward(reward);
            if (stack.isEmpty()) {
                HubSuite.logger().warn("任务 '{}' 的奖励 '{}' 解析失败，已跳过", quest.id, reward);
                continue;
            }
            give(player, stack);
            given.add(stack);
        }

        if (quest.kind == Quest.Kind.MILESTONE) {
            progress.done.add(quest.id);
        } else if (quest.kind == Quest.Kind.DAILY) {
            progress.dailyDone.add(quest.id);
        } else {
            // 可重复：把计数清零，方便再交
            progress.counters.remove(quest.counterKey());
        }
        save();

        if (config.announceOnComplete) {
            player.sendSystemMessage(cn.dreamgary.hubsuite.ui.Text.of(
                    "§a✔ 完成任务 §f" + cn.dreamgary.hubsuite.ui.Text.stripColors(quest.name)
                            + (given.isEmpty() ? "" : "§a，获得奖励")));
            for (ItemStack stack : given) {
                player.sendSystemMessage(cn.dreamgary.hubsuite.ui.Text.of(
                        "   §7- §f" + stack.getCount() + " × "
                                + stack.getHoverName().getString()));
            }
        }
        return null;   // null = 成功
    }

    /** 从背包扣除指定数量物品。 */
    private void consume(ServerPlayer player, Item item, int amount) {
        int left = amount;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.is(item)) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        inventory.setChanged();
        player.containerMenu.broadcastChanges();
    }

    /** 给玩家物品，装不下的掉在脚下。 */
    private void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    // ------------------------------------------------------------------
    // 解析工具
    // ------------------------------------------------------------------

    /** 解析物品 id（支持省略命名空间）。 */
    public static Item itemOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        Identifier id = text.contains(":")
                ? Identifier.tryParse(text)
                : Identifier.withDefaultNamespace(text);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
    }

    /**
     * 解析奖励字符串，形如 {@code minecraft:diamond*3}（数量可省，默认 1）。
     *
     * <p>数量会被夹到**该物品的最大堆叠数**，避免出现"一槽 256 个石头"
     * 这种客户端显示异常的状态。
     */
    public static ItemStack parseReward(String raw) {
        if (raw == null || raw.isBlank()) {
            return ItemStack.EMPTY;
        }
        String text = raw.trim();
        int amount = 1;
        int star = text.lastIndexOf('*');
        if (star > 0) {
            try {
                amount = Integer.parseInt(text.substring(star + 1).trim());
                text = text.substring(0, star).trim();
            } catch (NumberFormatException ignored) {
                // 解析失败就按 1 个
            }
        }
        Item item = itemOf(text);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack probe = new ItemStack(item);
        int max = Math.max(1, probe.getMaxStackSize());
        return new ItemStack(item, Math.max(1, Math.min(amount, max)));
    }

    /** 今天是不是任务日的第一天（诊断用）。 */
    public String debugDay() {
        return currentDay() + "（重置点 " + config.dailyResetHour + ":00）";
    }

    /** LocalDate 便于测试。 */
    public static String dayOf(LocalDate date) {
        return date.toString();
    }
}
