package cn.dreamgary.hubsuite.quest;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.ui.ChestMenuScreen;
import cn.dreamgary.hubsuite.ui.Text;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务界面（箱子式）。
 *
 * <p>一页显示 45 个任务（5 行内容 + 底部导航行）。布局：
 * <pre>
 *   第 0 行起：按 里程碑 → 每日 → 交付 的顺序排列任务条目
 *   最后一行：上一页 / 分类筛选 / 下一页 / 关闭
 * </pre>
 *
 * <p>点条目：
 * <ul>
 *   <li>已完成但没领奖 → 领奖；</li>
 *   <li>交付类且材料够 → 交付并领奖；</li>
 *   <li>未完成 → 只显示进度（不做事）。</li>
 * </ul>
 */
public final class QuestMenu {

    /** 每页条目数（留出最后一行做导航）。 */
    private static final int PAGE_SIZE = 45;

    /** 当前查看的分类（按玩家记，避免翻页时丢失）。 */
    private static final java.util.Map<java.util.UUID, Quest.Kind> FILTER =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Integer> PAGE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private QuestMenu() {
    }

    public static void forget(java.util.UUID uuid) {
        FILTER.remove(uuid);
        PAGE.remove(uuid);
    }

    /** 打开任务界面。 */
    public static void open(ServerPlayer player, Quest.Kind filter, int page) {
        var manager = HubSuite.quests();
        if (manager == null || !manager.config().enabled) {
            player.sendSystemMessage(Text.of("\u00A7c任务系统未启用。"));
            return;
        }
        if (filter == null) {
            filter = FILTER.getOrDefault(player.getUUID(), Quest.Kind.MILESTONE);
        }
        FILTER.put(player.getUUID(), filter);

        List<Quest> list = manager.config().ofKind(filter);
        int pages = Math.max(1, (list.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        PAGE.put(player.getUUID(), page);

        List<ChestMenuScreen.Entry> entries = new ArrayList<>();
        int from = page * PAGE_SIZE;
        for (int i = from; i < Math.min(list.size(), from + PAGE_SIZE); i++) {
            entries.add(entryFor(player, manager, list.get(i)));
        }

        // 底部导航行（补齐到整行，槽位固定）
        while (entries.size() < PAGE_SIZE) {
            entries.add(placeholder());
        }
        entries.add(navButton("\u00A77◀ 上一页", page > 0 ? page - 1 : -1, filter, player));
        entries.add(filterButton(manager, filter, player));
        entries.add(navButton("\u00A77下一页 ▶", page < pages - 1 ? page + 1 : -1, filter, player));
        entries.add(ChestMenuScreen.Entry.of(
                new ItemStack(Items.BARRIER),
                "\u00A7c关闭",
                List.of("\u00A77按 ESC 也可以关闭"),
                p -> ChestMenuScreen.forget(p.getUUID())));

        ChestMenuScreen.open(player, "\u00A78\u00A7l空岛任务 \u00A77(" + kindName(filter)
                + " " + (page + 1) + "/" + pages + ")", entries);
    }

    // ------------------------------------------------------------------

    private static ChestMenuScreen.Entry entryFor(ServerPlayer player, QuestManager manager,
                                                  Quest quest) {
        int progress = manager.progressOf(player, quest);
        boolean claimed = manager.isClaimed(player, quest);
        boolean ready = !claimed && manager.isComplete(player, quest);

        List<String> lore = new ArrayList<>();
        if (quest.description != null && !quest.description.isBlank()) {
            lore.add("\u00A77" + Text.stripColors(quest.description));
        }
        lore.add("");

        // 进度条
        lore.add("\u00A78进度：\u00A7f" + progress + " \u00A78/ \u00A7f" + quest.amount
                + " " + bar(progress, quest.amount));
        for (String goalLine : List.of(goalHint(quest))) {
            if (!goalLine.isEmpty()) {
                lore.add("\u00A78目标：" + goalLine);
            }
        }

        if (!quest.rewards.isEmpty()) {
            StringBuilder rewards = new StringBuilder("\u00A78奖励：");
            for (String reward : quest.rewards) {
                rewards.append("\u00A77").append(reward.replace("minecraft:", "")).append(' ');
            }
            lore.add(rewards.toString().trim());
        }
        lore.add("");

        if (claimed) {
            lore.add("\u00A7a✔ 已完成");
        } else if (quest.kind == Quest.Kind.DELIVER) {
            lore.add(manager.canDeliver(player, quest)
                    ? "\u00A7e▶ 点击交付并领取"
                    : "\u00A7c材料不够");
        } else if (ready) {
            lore.add("\u00A7e▶ 点击领取奖励");
        } else {
            lore.add("\u00A78尚未完成");
        }

        String name = (claimed ? "\u00A78✔ " : ready ? "\u00A7e✦ " : "\u00A7f")
                + Text.stripColors(quest.name);

        return ChestMenuScreen.Entry.of(iconFor(quest), name, lore, p -> {
            var mgr = HubSuite.quests();
            if (mgr == null) {
                return;
            }
            String error = mgr.claim(p, quest);
            if (error != null) {
                p.sendSystemMessage(Text.of(error));
            }
            // 重新打开以刷新状态
            open(p, quest.kind, PAGE.getOrDefault(p.getUUID(), 0));
        });
    }

    private static ItemStack iconFor(Quest quest) {
        ItemStack stack = QuestEvents.iconOf(quest.icon);
        if (stack.isEmpty()) {
            stack = new ItemStack(Items.PAPER);
        }
        return stack;
    }

    /** 进度条（10 格）。 */
    private static String bar(int progress, int total) {
        if (total <= 0) {
            return "";
        }
        int filled = (int) Math.round(10.0 * Math.min(progress, total) / total);
        StringBuilder sb = new StringBuilder("\u00A78[");
        sb.append("\u00A7a");
        for (int i = 0; i < 10; i++) {
            if (i == filled) {
                sb.append("\u00A78");
            }
            sb.append('|');
        }
        return sb.append("\u00A78]").toString();
    }

    /** 把目标翻译成人话。 */
    private static String goalHint(Quest quest) {
        String arg = quest.goalArg().replace("minecraft:", "");
        return switch (quest.goalType()) {
            case BREAK -> "\u00A7f破坏 " + arg;
            case PLACE -> "\u00A7f放置 " + arg;
            case KILL -> "\u00A7f击杀 " + arg;
            case HAVE -> "\u00A7f持有 " + arg;
            case DELIVER -> "\u00A7f交付 " + arg;
            case REACH_Y -> "\u00A7f到达 Y=" + arg;
            case ENTER -> "\u00A7f进入 " + arg;
            case UNKNOWN -> "";
        };
    }

    private static ChestMenuScreen.Entry placeholder() {
        return ChestMenuScreen.Entry.of(new ItemStack(Items.GRAY_STAINED_GLASS_PANE),
                " ", List.of(), p -> {
                });
    }

    private static ChestMenuScreen.Entry navButton(String label, int targetPage,
                                                   Quest.Kind filter, ServerPlayer player) {
        boolean disabled = targetPage < 0;
        return ChestMenuScreen.Entry.of(
                new ItemStack(disabled ? Items.GRAY_DYE : Items.ARROW),
                disabled ? "\u00A78" + Text.stripColors(label) : label,
                List.of(disabled ? "\u00A78没有更多了" : "\u00A77点击翻页"),
                disabled ? p -> {
                } : p -> open(p, filter, targetPage));
    }

    /** 分类切换按钮：每点一次换到下一种。 */
    private static ChestMenuScreen.Entry filterButton(QuestManager manager, Quest.Kind current,
                                                     ServerPlayer player) {
        Quest.Kind next = switch (current) {
            case MILESTONE -> Quest.Kind.DAILY;
            case DAILY -> Quest.Kind.DELIVER;
            case DELIVER -> Quest.Kind.MILESTONE;
        };
        int count = manager.config().ofKind(current).size();
        return ChestMenuScreen.Entry.of(
                new ItemStack(switch (current) {
                    case MILESTONE -> Items.NETHER_STAR;
                    case DAILY -> Items.CLOCK;
                    case DELIVER -> Items.CHEST;
                }),
                "\u00A7b" + kindName(current) + " \u00A77(" + count + ")",
                List.of("\u00A77点击切换到：" + kindName(next)),
                p -> open(p, next, 0));
    }

    private static String kindName(Quest.Kind kind) {
        return switch (kind) {
            case MILESTONE -> "里程碑";
            case DAILY -> "每日任务";
            case DELIVER -> "物资交付";
        };
    }
}
