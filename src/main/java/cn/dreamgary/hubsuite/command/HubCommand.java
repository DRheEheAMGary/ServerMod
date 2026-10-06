package cn.dreamgary.hubsuite.command;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.auth.AuthGui;
import cn.dreamgary.hubsuite.auth.AuthManager;
import cn.dreamgary.hubsuite.auth.AuthService;
import cn.dreamgary.hubsuite.config.ConfigManager;
import cn.dreamgary.hubsuite.ui.DialogRouter;
import cn.dreamgary.hubsuite.world.Lobby;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.world.PlayerRouter;
import cn.dreamgary.hubsuite.world.RulesManager;
import cn.dreamgary.hubsuite.world.SelfTest;
import cn.dreamgary.hubsuite.world.SubServer;
import cn.dreamgary.hubsuite.world.WorldsManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import cn.dreamgary.hubsuite.ui.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * {@code /hub} —— **只负责传送**。
 *
 * <p>职责刻意收窄，其他功能都拆到了独立指令：
 * <ul>
 *   <li>服务器选择界面 → {@code /menu}（见 {@link MenuCommand}）；</li>
 *   <li>账号管理 → {@code /auth}（见 {@link AuthCommand}）；</li>
 *   <li>空岛功能 → {@code /island}（只在空岛服可用）。</li>
 * </ul>
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code /hub} —— 回大厅；</li>
 *   <li>{@code /hub <服务器>} —— 直达某个子服；</li>
 *   <li>{@code /hub list} —— 列出所有服务器；</li>
 *   <li>{@code /hub where} —— 我现在在哪。</li>
 * </ul>
 */
public final class HubCommand {

    private final WorldsManager worlds;
    private final ConfigManager configManager;
    private final AuthService auth;
    private final AuthManager authManager;

    public HubCommand(WorldsManager worlds, ConfigManager configManager,
                      AuthService auth, AuthManager authManager) {
        this.worlds = worlds;
        this.configManager = configManager;
        this.auth = auth;
        this.authManager = authManager;
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("hub")
                    .executes(ctx -> toWorld(ctx.getSource(), Lobby.ID))
                    .then(Commands.argument("server", StringArgumentType.word())
                            .suggests((ctx, builder) -> {
                                worlds.subServers().forEach(sub -> builder.suggest(sub.id()));
                                builder.suggest(Lobby.ID);
                                return builder.buildFuture();
                            })
                            .executes(ctx -> toWorld(ctx.getSource(), StringArgumentType.getString(ctx, "server"))))
                    .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                    .then(Commands.literal("where").executes(ctx -> where(ctx.getSource())))
                    // 自检：不是给玩家用的功能，而是回归测试入口（需要管理员权限）
                    .then(Commands.literal("selftest")
                            .requires(HubCommand::isAdmin)
                            .executes(ctx -> selfTest(ctx.getSource()))));
        });
    }

    // ------------------------------------------------------------------
    // 传送
    // ------------------------------------------------------------------

    /** 把执行者送到目标场所。 */
    private int toWorld(CommandSourceStack source, String id) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("\u00A7c这个指令只能由玩家执行。"));
            return 0;
        }
        if (!worlds.isReady()) {
            source.sendFailure(Component.literal("\u00A7cHubSuite 尚未就绪（大厅未加载），请联系管理员。"));
            return 0;
        }
        if (authManager != null && !authManager.isAuthenticated(player)) {
            HubSuite.logger().info("/hub {} 被拒：{} 未通过认证", id, player.getName().getString());
            source.sendFailure(Component.literal("\u00A7c请先完成注册或登录。"));
            return 0;
        }

        PlayableWorld target = worlds.worldById(id).orElse(null);
        if (target == null) {
            HubSuite.logger().info("/hub {} 被拒：未知子服（已加载：{}）", id,
                    worlds.allWorlds().stream().map(PlayableWorld::id).toList());
            source.sendFailure(Component.literal("\u00A7c未知的子服：\u00A7f" + id));
            return 0;
        }

        var current = worlds.worldOf(player).orElse(null);
        if (current != null && current.id().equals(target.id())) {
            source.sendSuccess(() -> Text.of("\u00A77你已经在 \u00A7f" + target.displayName() + " \u00A77了。"), false);
            return 1;
        }

        // 传送到目标场所。不清空任何玩家状态 —— 背包/经验/属性完整保留。
        if (!PlayerRouter.sendTo(player, target)) {
            return 0;
        }
        source.sendSuccess(() -> Text.of("\u00A7a已传送到 \u00A7f" + target.displayName() + "\u00A7a。"), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // 自检（回归测试入口）
    // ------------------------------------------------------------------

    /**
     * 供程序化调用（无人值守跑自检）：执行一次自检，结果进日志与控制台。
     *
     * <p>存在的理由：脚本想让服务端"起来就自己跑一次自检"时，走 stdin 或 RCON
     * 都不稳 —— 26.1.2 的 RCON 只在极窄时序下回包，而 Gradle 包装层会抢走 stdin
     * （实测命令行被吃掉）。直接调这个方法最可靠。
     */
    public boolean runSelfTestProgrammatically() {
        var server = worlds.server();
        if (server == null) {
            HubSuite.logger().error("自检无法运行：服务端尚未就绪");
            return false;
        }
        try {
            selfTest(server.createCommandSourceStack());
            return true;
        } catch (Throwable t) {
            HubSuite.logger().error("自检执行失败", t);
            return false;
        }
    }

    private int selfTest(CommandSourceStack source) {
        if (!worlds.isReady()) {
            source.sendFailure(Component.literal("\u00A7cHubSuite 尚未就绪。"));
            return 0;
        }
        if (runningSelfTest != null && !runningSelfTest.done) {
            source.sendFailure(Component.literal("\u00A7c自检正在运行中，请稍候（等待世界生成的部分可能要点时间）。"));
            return 0;
        }
        var test = new SelfTest(worlds.server(), worlds, auth, authManager);
        test.run();
        if (test.pendingCount() > 0) {
            // 有"等世界生成"的项：**不能阻塞主线程**（那样区块永远生成不完，
            // 就是早先被看门狗强杀的原因）。交给 tick 泵逐帧推进，完成后自动出报告。
            var job = new SelfTestJob(test, source);
            runningSelfTest = job;
            source.sendSuccess(() -> Component.literal("\u00A77自检已跑到需要世界生成的部分（"
                    + test.pendingCount() + " 项等待中）—— 生成完会自动出完整报告，其间服务器正常运行。"), false);
            return 1;
        }
        reportSelfTest(test, source);
        return test.failed() == 0 ? 1 : 0;
    }

    /** 把自检结果发给命令来源（控制台也会走这里）。 */
    private void reportSelfTest(SelfTest test, CommandSourceStack source) {
        boolean allPassed = test.logSummary();
        source.sendSuccess(() -> Component.literal("\u00A78\u00A7m------\u00A7r \u00A7bHubSuite 自检报告 \u00A78\u00A7m------"), false);
        test.results().forEach(row -> source.sendSuccess(() -> Component.literal(row), false));
        source.sendSuccess(() -> Component.literal(allPassed
                ? "\u00A7a全部通过（" + test.passed() + " 项）"
                : "\u00A7c存在失败项：通过 " + test.passed() + "，失败 " + test.failed()), false);
    }

    /**
     * 一次跨 tick 的自检任务。
     *
     * <p>存在的理由：自检里有几项要等世界生成（冷存档上区块要现生成），
     * 而**不能**在主线程上等 —— 区块生成要主线程参与，占着不放就永远等不到，
     * 实测直接把服务端卡到看门狗强杀。所以跑完同步部分后把结果挂起，
     * 由 {@link #registerSelfTestPump()} 注册的 tick 回调逐帧推进。
     */
    private static final class SelfTestJob {
        final SelfTest test;
        final CommandSourceStack source;
        volatile boolean done;

        SelfTestJob(SelfTest test, CommandSourceStack source) {
            this.test = test;
            this.source = source;
        }
    }

    private static SelfTestJob runningSelfTest;

    /**
     * 注册自检的 tick 泵。在模组初始化时调一次即可。
     *
     * <p>每 tick 推进少量等待项（一次处理太多又会变成"一个 tick 干太多"，
     * 绕回看门狗那个坑）。
     */
    public static void registerSelfTestPump() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            SelfTestJob job = runningSelfTest;
            if (job == null || job.done) {
                return;
            }
            job.test.pumpDeferred();
            if (job.test.pendingCount() > 0) {
                return;
            }
            job.done = true;
            runningSelfTest = null;
            try {
                boolean allPassed = job.test.logSummary();
                HubSuite.logger().info("自检（跨 tick 部分）结束：通过 {} 项，失败 {} 项。",
                        job.test.passed(), job.test.failed());
                job.source.sendSuccess(() -> Component.literal(
                        allPassed ? "\u00A7a自检全部通过（" + job.test.passed() + " 项）"
                                  : "\u00A7c自检存在失败项：通过 " + job.test.passed()
                                    + "，失败 " + job.test.failed()), false);
            } catch (Throwable t) {
                HubSuite.logger().error("自检收尾失败", t);
            }
        });
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    private int list(CommandSourceStack source) {
        if (!worlds.isReady()) {
            source.sendFailure(Component.literal("\u00A7cHubSuite 尚未就绪。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("\u00A78\u00A7m--------\u00A7r \u00A7bHubSuite 服务器列表 \u00A78\u00A7m--------"), false);
        worlds.lobby().ifPresent(lobby -> source.sendSuccess(() -> Text.of(
                " \u00A78- \u00A7f" + lobby.displayName() + " \u00A77(/hub) \u00A7f"
                        + lobby.level().players().size() + " 人"), false));
        for (SubServer sub : worlds.subServers()) {
            if (!sub.config().enabled) {
                continue;
            }
            source.sendSuccess(() -> Text.of(
                    " \u00A78- " + sub.displayName() + " \u00A77(/hub " + sub.id() + ") \u00A7f"
                            + sub.playerCount() + " 人"), false);
        }
        source.sendSuccess(() -> Component.literal("\u00A77点大厅里的假人，或用 \u00A7f/menu \u00A77打开选择界面。"), false);
        return 1;
    }

    private int where(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("\u00A7c这个指令只能由玩家执行。"));
            return 0;
        }
        PlayableWorld current = worlds.worldOf(player).orElse(null);
        if (current == null) {
            source.sendFailure(Component.literal("\u00A7c你现在不在任何托管维度里。"));
            return 0;
        }
        var pos = player.blockPosition();
        source.sendSuccess(() -> Text.of(String.format(java.util.Locale.ROOT,
                "\u00A77你当前在 %s \u00A78(%s)\u00A77，坐标 \u00A7f%d %d %d",
                current.displayName(), current.id(), pos.getX(), pos.getY(), pos.getZ())), false);
        return 1;
    }
}
