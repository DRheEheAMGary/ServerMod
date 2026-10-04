package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

/**
 * 大厅环境控制：**永昼 + 无雨**。
 *
 * <p><b>为什么需要这个类：</b>大厅改用 {@code minecraft:overworld} 维度类型后
 * （末地类型会强制生成黑曜石平台和末影龙），就继承了主世界的天气与昼夜 ——
 * 实测出现过"大厅是夜晚还下着雨"，玩家一进来就以为服务器坏了。
 *
 * <p>三件事一起做才有效：
 * <ol>
 *   <li>{@code ADVANCE_WEATHER=false} —— 关掉天气推进（gamerule 层）；</li>
 *   <li>{@link ServerLevel#resetWeatherCycle()} —— 把当前天气立即恢复成晴朗。
 *       只关 gamerule 不够：如果进服那一刻正好在下雨，雨会一直下；</li>
 *   <li>把世界时钟跳到正午并暂停 —— 26.1 的昼夜由 {@code WorldClock} 管理，
 *       把时间设到 NOON 再 {@code setPaused(true)}，大厅就永远是白天。</li>
 * </ol>
 */
public final class LobbyEnvironment {

    private LobbyEnvironment() {
    }

    /** 把大厅设成永昼 + 晴朗。可在启动时与每次有玩家进入时重复调用。 */
    public static void makeEternalDay(ServerLevel level) {
        clearWeather(level);
        freezeClockAtNoon(level);
    }

    /** 立即放晴。 */
    public static void clearWeather(ServerLevel level) {
        try {
            level.resetWeatherCycle();
            level.setRainLevel(0.0F);
            level.setThunderLevel(0.0F);
        } catch (Throwable t) {
            HubSuite.logger().warn("大厅放晴失败：{}", t.toString());
        }
    }

    /**
     * 把主世界时钟拨到正午并暂停。
     *
     * <p>注意：<b>不要用 {@code moveToTimeMarker(NOON)}</b> —— 它的语义是
     * "前进到下一个正午"，每次调用都会把时钟推进一整天
     * （实测出现过 30000 → 54000 → 78000 这种一跳一天）。这里改为
     * 按"当天正午"直接算目标刻数，可重复调用且结果稳定。
     */
    public static void freezeClockAtNoon(ServerLevel level) {
        try {
            ServerClockManager clocks = level.clockManager();
            Holder<WorldClock> clock = level.getServer().registryAccess()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
                    .getOrThrow(WorldClocks.OVERWORLD);

            long now = clocks.getTotalTicks(clock);
            long noon = noonOf(now);
            if (now != noon) {
                clocks.setTotalTicks(clock, noon);
            }
            clocks.setPaused(clock, true);

            boolean paused = clocks.getTotalTicks(clock) == noon;
            if (paused) {
                HubSuite.logger().debug("大厅永昼已就绪：刻数 {}（当天正午）。", noon);
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("大厅永昼设置失败（不影响其它功能）：{}", t.toString());
        }
    }

    /** 一天 24000 刻，6000 刻为正午；取"当天"的正午，保证结果不随时钟漂移。 */
    private static long noonOf(long totalTicks) {
        long day = Math.floorDiv(totalTicks, 24000L);
        return day * 24000L + 6000L;
    }
}
