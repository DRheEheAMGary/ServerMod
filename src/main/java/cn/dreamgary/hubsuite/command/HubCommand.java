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

    private int selfTest(CommandSourceStack source) {
        if (!worlds.isReady()) {
            source.sendFailure(Component.literal("\u00A7cHubSuite 尚未就绪。"));
            return 0;
        }
        var test = new SelfTest(worlds.server(), worlds, auth, authManager);
        test.run();
        boolean allPassed = test.logSummary();
        source.sendSuccess(() -> Component.literal("\u00A78\u00A7m------\u00A7r \u00A7bHubSuite 自检报告 \u00A78\u00A7m------"), false);
        test.results().forEach(row -> source.sendSuccess(() -> Component.literal(row), false));
        source.sendSuccess(() -> Component.literal(allPassed
                ? "\u00A7a全部通过（" + test.passed() + " 项）"
                : "\u00A7c存在失败项：通过 " + test.passed() + "，失败 " + test.failed()), false);
        return allPassed ? 1 : 0;
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
                            + sub.level().players().size() + " 人"), false);
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
