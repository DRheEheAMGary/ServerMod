package cn.dreamgary.hubsuite.command;

import cn.dreamgary.hubsuite.auth.AuthManager;
import cn.dreamgary.hubsuite.auth.AuthService;
import cn.dreamgary.hubsuite.world.WorldsManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /auth} —— 账号管理（管理员），外加 {@code /login}、{@code /register} 兜底命令。
 *
 * <p>权限：管理子命令需要 Game Master（等级 2）；{@code /login}、{@code /register}
 * 必须对所有人开放（否则没登录的玩家没法登录）。
 *
 * <p>为什么保留聊天命令：万一玩家客户端没能弹出对话框
 * （例如某些会屏蔽自定义界面的客户端模组），密码仍有可用通道。
 * 代价是密码会进入该玩家的聊天记录 —— 所以对话框是首选，命令只是后备。
 */
public final class AuthCommand {

    private final WorldsManager worlds;
    private final AuthService auth;
    private final AuthManager authManager;

    public AuthCommand(WorldsManager worlds, AuthService auth, AuthManager authManager) {
        this.worlds = worlds;
        this.auth = auth;
        this.authManager = authManager;
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(Commands.literal("auth")
                    .requires(AuthCommand::isAdmin)
                    .executes(ctx -> authList(ctx.getSource()))
                    .then(Commands.literal("list").executes(ctx -> authList(ctx.getSource())))
                    .then(Commands.literal("reset")
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .then(Commands.argument("password", StringArgumentType.greedyString())
                                            .executes(ctx -> authReset(ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "player"),
                                                    StringArgumentType.getString(ctx, "password"))))))
                    .then(Commands.literal("delete")
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .executes(ctx -> authDelete(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "player")))))
                    .then(Commands.literal("kick")
                            .then(Commands.argument("player", StringArgumentType.word())
                                    .executes(ctx -> authKick(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "player"))))));

            // 兜底命令（等级 0，未登录也能用）
            dispatcher.register(Commands.literal("register")
                    .then(Commands.argument("password", StringArgumentType.string())
                            .then(Commands.argument("confirm", StringArgumentType.string())
                                    .executes(ctx -> chatRegister(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "password"),
                                            StringArgumentType.getString(ctx, "confirm"))))));
            dispatcher.register(Commands.literal("login")
                    .then(Commands.argument("password", StringArgumentType.string())
                            .executes(ctx -> chatLogin(ctx.getSource(),
                                    StringArgumentType.getString(ctx, "password")))));
        });
    }

    // ------------------------------------------------------------------

    private int authList(CommandSourceStack source) {
        int total = auth.accountCount();
        source.sendSuccess(() -> Component.literal("\u00A77已注册账号：\u00A7f" + total
                + " \u00A77| 存储：\u00A7f" + (auth.store().usingFallback()
                ? "JSON 兜底（SQLite 不可用）" : auth.store().databaseFile())), false);
        return 1;
    }

    private int authReset(CommandSourceStack source, String player, String password) {
        var result = auth.resetPassword(player, password);
        if (result.ok()) {
            source.sendSuccess(() -> Component.literal("\u00A7a" + result.message()), true);
        } else {
            source.sendFailure(Component.literal("\u00A7c" + result.message()));
        }
        return result.ok() ? 1 : 0;
    }

    private int authDelete(CommandSourceStack source, String player) {
        var result = auth.deleteAccount(player);
        if (result.ok()) {
            source.sendSuccess(() -> Component.literal("\u00A7a" + result.message()), true);
        } else {
            source.sendFailure(Component.literal("\u00A7c" + result.message()));
        }
        return result.ok() ? 1 : 0;
    }

    private int authKick(CommandSourceStack source, String player) {
        var server = worlds.server();
        if (server == null) {
            source.sendFailure(Component.literal("\u00A7c服务端未就绪。"));
            return 0;
        }
        ServerPlayer target = server.getPlayerList().getPlayers().stream()
                .filter(p -> p.getName().getString().equalsIgnoreCase(player))
                .findFirst().orElse(null);
        if (target == null) {
            source.sendFailure(Component.literal("\u00A7c玩家不在线：" + player));
            return 0;
        }
        auth.invalidateSession(target.getUUID());
        target.connection.disconnect(Component.literal("\u00A7e你的登录会话已被管理员重置，请重新登录。"));
        source.sendSuccess(() -> Component.literal("\u00A7a已让 " + player + " 重新登录。"), true);
        return 1;
    }

    private int chatRegister(CommandSourceStack source, String password, String confirm) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
        var result = auth.register(player.getName().getString(), player.getUUID(),
                password, confirm, AuthService.ipOf(player.connection.getRemoteAddress()));
        if (result.ok()) {
            authManager.authenticated(player);
            source.sendSuccess(() -> Component.literal(
                    "\u00A7a注册成功。\u00A77提示：优先使用弹出的对话框，可避免密码留在聊天记录里。"), false);
        } else {
            source.sendFailure(Component.literal("\u00A7c" + result.message()));
        }
        return result.ok() ? 1 : 0;
    }

    private int chatLogin(CommandSourceStack source, String password) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
        var result = auth.login(player.getName().getString(), player.getUUID(),
                password, AuthService.ipOf(player.connection.getRemoteAddress()));
        if (result.ok()) {
            authManager.authenticated(player);
            source.sendSuccess(() -> Component.literal("\u00A7a登录成功。"), false);
        } else {
            source.sendFailure(Component.literal("\u00A7c" + result.message()));
        }
        return result.ok() ? 1 : 0;
    }
}
