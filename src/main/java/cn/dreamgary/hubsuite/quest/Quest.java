package cn.dreamgary.hubsuite.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一个任务的定义（可写进 config.json，管理员不用改代码就能加任务）。
 *
 * <p>三种类型：
 * <ul>
 *   <li>{@code milestone} —— 一次性里程碑，完成即永久打勾；</li>
 *   <li>{@code deliver} —— 可重复交付物品换奖励；</li>
 *   <li>{@code daily} —— 每日任务，跨天自动重置。</li>
 * </ul>
 *
 * <p>目标写成一行字符串，便于手改配置：{@code 类型:参数}。
 * <pre>
 *   break:minecraft:stone        挖掉指定方块
 *   place:minecraft:cobblestone  放置指定方块
 *   kill:minecraft:zombie        击杀指定生物
 *   have:minecraft:diamond       背包里持有（当前数量，不消耗）
 *   deliver:minecraft:cobblestone 交付（会从背包扣除）
 *   reach_y:120                  到达指定高度
 *   enter:ocean                  进入指定子服（岛屿维度）
 * </pre>
 */
public final class Quest {

    public enum Kind {
        MILESTONE, DELIVER, DAILY;

        public static Kind parse(String raw, Kind fallback) {
            if (raw == null) {
                return fallback;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "milestone", "one_time", "once" -> MILESTONE;
                case "deliver", "repeat", "repeatable" -> DELIVER;
                case "daily" -> DAILY;
                default -> fallback;
            };
        }
    }

    /** 目标类型。 */
    public enum GoalType {
        BREAK, PLACE, KILL, HAVE, DELIVER, REACH_Y, ENTER, UNKNOWN;

        public static GoalType parse(String raw) {
            if (raw == null) {
                return UNKNOWN;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "break", "mine" -> BREAK;
                case "place" -> PLACE;
                case "kill" -> KILL;
                case "have", "obtain", "collect" -> HAVE;
                case "deliver", "submit", "hand_in" -> DELIVER;
                case "reach_y", "reachy", "height" -> REACH_Y;
                case "enter", "visit" -> ENTER;
                default -> UNKNOWN;
            };
        }
    }

    // ------------------------------------------------------------------

    public String id = "";
    public Kind kind = Kind.MILESTONE;
    /** 显示名，支持 & / § 颜色码。 */
    public String name = "";
    public String description = "";
    /** 图标物品 id，例如 {@code minecraft:stone}。 */
    public String icon = "minecraft:paper";
    /** 目标，形如 {@code break:minecraft:stone}。 */
    public String goal = "";
    /** 需要的数量。 */
    public int amount = 1;
    /** 奖励，形如 {@code minecraft:diamond*3}。 */
    public List<String> rewards = new ArrayList<>();
    /** 前置任务 id（可空）。 */
    public String requires = "";

    // 解析结果（不进 JSON，运行时填充）
    private transient GoalType goalType = GoalType.UNKNOWN;
    private transient String goalArg = "";

    public GoalType goalType() {
        return goalType;
    }

    public String goalArg() {
        return goalArg;
    }

    /** 计数器用的键（去掉数量，只保留"目标本身"）。 */
    public String counterKey() {
        return goalType.name().toLowerCase(Locale.ROOT) + ":" + goalArg;
    }

    /** 把 {@code goal} 拆成类型与参数，并做基本校验。 */
    public void compile() {
        goalType = GoalType.UNKNOWN;
        goalArg = "";
        if (goal == null || goal.isBlank()) {
            return;
        }
        String text = goal.trim();
        int colon = text.indexOf(':');
        if (colon < 0) {
            goalType = GoalType.parse(text);
            return;
        }
        goalType = GoalType.parse(text.substring(0, colon));
        goalArg = text.substring(colon + 1).trim();
    }

    /** 配置是否可用（编译后调用）。 */
    public boolean valid() {
        return id != null && !id.isBlank()
                && goalType != GoalType.UNKNOWN
                && amount > 0;
    }

    public boolean repeatable() {
        return kind != Kind.MILESTONE;
    }
}
