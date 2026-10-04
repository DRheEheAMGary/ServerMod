package cn.dreamgary.hubsuite.island;

import cn.dreamgary.hubsuite.feature.PermissionService;
import cn.dreamgary.hubsuite.world.PlayerRouter;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * {@code /island} 命令族（空岛服专属，其它子服执行会提示）。
 *
 * <p>指令集：
 * <pre>
 * /island                  帮助（列出下面这些）
 * /island hub              回空岛服大厅
 * /island classic          去经典空岛（没有就建一座）
 * /island ocean            去海岛（没有就建一座）
 * /island home             回上次那种岛
 * /island info             我的岛信息
 * /island reset &lt;类型&gt;     重置我的岛（清地形重建，谨慎）
 * /island types            列出所有岛型
 * </pre>
 *
 * <p>只能在空岛服相关维度里使用（大厅或两种岛屿维度都行）。
 */
public final class IslandCommand {

    /**
     * 空岛服务的提供者。
     *
     * <p>用 supplier 而不是直接持有实例：指令必须在模组初始化阶段注册，
     * 而 {@link IslandService} 要等服务端启动（子服建好）才能创建。
     * 执行指令时再去拿，拿不到就给玩家一句友好的提示。
     */
    private final java.util.function.Supplier<IslandService> serviceSupplier;
    private final PermissionService permissions;

    public IslandCommand(java.util.function.Supplier<IslandService> serviceSupplier,
                         PermissionService permissions) {
        this.serviceSupplier = serviceSupplier;
        this.permissions = permissions;
    }

    /** 取空岛服务；未就绪时返回 null。 */
    private IslandService service() {
        return serviceSupplier.get();
    }

    public void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("island")
                        .executes(ctx -> ctx.getSource().getPlayer() == null
                                ? help(ctx.getSource())      // 控制台：直接列帮助
                                : home(ctx.getSource(), null))   // 玩家：回上次那种岛
                        .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                        .then(Commands.literal("hub").executes(ctx -> hub(ctx.getSource())))
                        .then(Commands.literal("home").executes(ctx -> home(ctx.getSource(), null)))
                        .then(Commands.literal("classic").executes(ctx -> home(ctx.getSource(), "classic")))
                        .then(Commands.literal("ocean").executes(ctx -> home(ctx.getSource(), "ocean")))
                        .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
                        .then(Commands.literal("types").executes(ctx -> types(ctx.getSource())))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests(IslandCommand::suggestTypes)
                                        .executes(ctx -> reset(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "type")))))));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions>
    suggestTypes(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                 com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        var islands = cn.dreamgary.hubsuite.HubSuite.islands();
        if (islands != null) {
            islands.manager().config().types.forEach(t -> builder.suggest(t.id));
        }
        return builder.buildFuture();
    }

    // ------------------------------------------------------------------

    private ServerPlayer player(CommandSourceStack source) throws Exception {
        return source.getPlayerOrException();
    }

    private boolean inSkyblock(ServerPlayer player) {
        return player.level().dimension().equals(service().server().level().dimension());
    }

    private int requireSkyblock(CommandSourceStack source, ServerPlayer player) {
        if (inSkyblock(player)) {
            return 1;
        }
        source.sendFailure(Component.literal("\u00A7c请先进入空岛服：\u00A7f/hub " + service().server().id()));
        return 0;
    }

    private int home(CommandSourceStack source, String typeId) {
        ServerPlayer player;
        try {
            player = player(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
        if (requireSkyblock(source, player) == 0) {
            return 0;
        }
        // 统一走 visit：它会登记目的地、建岛（没有的话）、并用 PlayerRouter
        // 完成完整的切服流程（出生点解析、状态隔离、重生点绑定都在里面）。
        if (!service().visit(player, typeId)) {
            source.sendFailure(Component.literal("\u00A7c传送失败，请查看控制台。"));
            return 0;
        }
        var island = service().islandAnywhere(player.getUUID()).orElse(null);
        if (island == null) {
            source.sendSuccess(() -> Component.literal("\u00A7a已传送到你的岛。"), false);
            return 1;
        }
        var type = service().type(island.type).orElse(null);
        String typeName = type == null ? island.type
                : cn.dreamgary.hubsuite.ui.Text.stripColors(
                        type.manager().config().type(island.type).displayName);
        source.sendSuccess(() -> Component.literal(
                "\u00A7a已进入你的" + typeName + " \u00A77(方格 " + island.plotX + ", " + island.plotZ + ")"), false);
        return 1;
    }

    /** 帮助。 */
    private int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "\u00A78\u00A7m--------\u00A7r \u00A7b空岛帮助 \u00A78\u00A7m--------"), false);
        for (String row : new String[]{
                " \u00A7f/island hub \u00A77- 回空岛服大厅",
                " \u00A7f/island classic \u00A77- 去经典空岛（没有就建一座）",
                " \u00A7f/island ocean \u00A77- 去海岛（没有就建一座）",
                " \u00A7f/island home \u00A77- 回上次那种岛",
                " \u00A7f/island info \u00A77- 我的岛信息",
                " \u00A7f/island types \u00A77- 列出所有岛型",
                " \u00A7f/island reset <类型> \u00A77- 重置我的岛（谨慎）"}) {
            source.sendSuccess(() -> Component.literal(row), false);
        }
        return 1;
    }

    /** 回空岛服大厅。 */

    /** 我的岛信息（两种岛型都列出来）。 */
    private int info(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = player(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
        var service = service();
        source.sendSuccess(() -> Component.literal(
                "\u00A78\u00A7m------\u00A7r \u00A7b我的岛屿 \u00A78\u00A7m------"), false);

        boolean any = false;
        for (var type : service.types()) {
            var manager = type.manager();
            var island = manager.islandOf(player.getUUID());
            var typeConfig = manager.config().type(type.id());
            String name = typeConfig == null ? type.id()
                    : cn.dreamgary.hubsuite.ui.Text.stripColors(typeConfig.displayName);
            if (island.isEmpty()) {
                source.sendSuccess(() -> Component.literal(
                        " \u00A78- \u00A7f" + name + " \u00A77：还没有（\u00A7f/island "
                                + type.id() + "\u00A77 创建）"), false);
                continue;
            }
            any = true;
            var isl = island.get();
            var center = manager.anchorOf(isl);
            source.sendSuccess(() -> Component.literal(
                    " \u00A78- \u00A7f" + name + " \u00A77：方格 (\u00A7f" + isl.plotX + ", "
                            + isl.plotZ + "\u00A77) 坐标 \u00A7f"
                            + center.getX() + " " + center.getY() + " " + center.getZ()), false);
        }
        if (!any) {
            source.sendSuccess(() -> Component.literal(
                    " \u00A77你还没有任何岛，用 \u00A7f/island classic\u00A77 或 \u00A7f/island ocean\u00A77 选一个。"), false);
        }
        return 1;
    }

    /** 列出所有岛型。 */
    private int types(CommandSourceStack source) {
        var service = service();
        source.sendSuccess(() -> Component.literal(
                "\u00A78\u00A7m------\u00A7r \u00A7b可用岛型 \u00A78\u00A7m------"), false);
        for (var type : service.types()) {
            var typeConfig = type.manager().config().type(type.id());
            if (typeConfig == null) {
                continue;
            }
            source.sendSuccess(() -> cn.dreamgary.hubsuite.ui.Text.of(
                    " \u00A78- " + typeConfig.displayName + " \u00A77(id=\u00A7f" + type.id()
                            + "\u00A77) 维度=\u00A7f" + type.entry().dimension().identifier()), false);
            source.sendSuccess(() -> cn.dreamgary.hubsuite.ui.Text.of(
                    "   \u00A77" + cn.dreamgary.hubsuite.ui.Text.stripColors(typeConfig.description)), false);
        }
        return 1;
    }

    /** 重置我的某种岛（清地形重建，谨慎）。 */
    private int reset(CommandSourceStack source, String typeId) {
        ServerPlayer player;
        try {
            player = player(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
        if (requireSkyblock(source, player) == 0) {
            return 0;
        }
        var service = service();
        var type = service.type(typeId).orElse(null);
        if (type == null) {
            source.sendFailure(Component.literal("\u00A7c没有这种岛型：\u00A7f" + typeId));
            return 0;
        }
        var manager = type.manager();
        if (manager.islandOf(player.getUUID()).isEmpty()) {
            source.sendFailure(Component.literal("\u00A7c你还没有" + typeId + "岛，没什么可重置的。"));
            return 0;
        }
        // 先送出这个维度，否则地形清空时人就站在空气里了
        service.sendToHub(player);
        manager.delete(player.getUUID());
        manager.getOrCreate(player.getUUID(), player.getName().getString(), type.id());
        source.sendSuccess(() -> Component.literal(
                "\u00A7a已重置你的" + typeId + "岛，用 \u00A7f/island " + typeId + "\u00A7a 回去。"), true);
        return 1;
    }

    private int hub(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = player(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
        if (requireSkyblock(source, player) == 0) {
            return 0;
        }
        if (!service().sendToHub(player)) {
            source.sendFailure(Component.literal("\u00A7c传送失败，请查看控制台。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("\u00A7a已回到空岛服大厅。"), false);
        return 1;
    }


    private int delete(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = player(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
        if (requireSkyblock(source, player) == 0) {
            return 0;
        }
        boolean removed = service().manager().delete(player.getUUID());
        if (removed) {
            var lobby = cn.dreamgary.hubsuite.HubSuite.worlds().lobby().orElse(null);
            if (lobby != null) {
                PlayerRouter.sendTo(player, lobby);
            }
            source.sendSuccess(() -> Component.literal("\u00A7a你的岛已删除（地形保留，可用 /island create 重新分配）。"), true);
        } else {
            source.sendFailure(Component.literal("\u00A7c你没有岛。"));
        }
        return removed ? 1 : 0;
    }



    private int invite(CommandSourceStack source, String targetName) {
        ServerPlayer player;
        try {
            player = player(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
        var island = service().manager().islandOf(player.getUUID()).orElse(null);
        if (island == null) {
            source.sendFailure(Component.literal("\u00A7c你还没有岛。"));
            return 0;
        }
        boolean added = service().manager().addMember(island, targetName);
        if (added) {
            source.sendSuccess(() -> Component.literal(
                    "\u00A7a已把 \u00A7f" + targetName + " \u00A7a加为岛员，他可以来你的岛一起建造。"), false);
        } else {
            source.sendFailure(Component.literal("\u00A7e该玩家已经是你的岛员了。"));
        }
        return added ? 1 : 0;
    }

    private int visit(CommandSourceStack source, String targetName) {
        ServerPlayer player;
        try {
            player = player(source);
        } catch (Exception e) {
            source.sendFailure(Component.literal("该命令只能由玩家执行。"));
            return 0;
        }
        if (requireSkyblock(source, player) == 0) {
            return 0;
        }
        var server = service().server().level().getServer();
        ServerPlayer target = server.getPlayerList().getPlayers().stream()
                .filter(p -> p.getName().getString().equalsIgnoreCase(targetName))
                .findFirst().orElse(null);
        if (target == null) {
            source.sendFailure(Component.literal("\u00A7c玩家不在线：" + targetName));
            return 0;
        }
        var island = service().manager().islandOf(target.getUUID()).orElse(null);
        if (island == null) {
            source.sendFailure(Component.literal("\u00A7c对方还没有岛。"));
            return 0;
        }
        // 访客默认没有建造权限，需要岛主 /island invite
        service().teleportHome(player, island);
        source.sendSuccess(() -> Component.literal(
                "\u00A7a已传送到 \u00A7f" + targetName + " \u00A7a的岛。\u00A77（未经邀请无法建造）"), false);
        return 1;
    }



    /** 供 /hub info 展示。 */
    public String summary() {
        return String.format(Locale.ROOT, "%d 座岛 / %d 种岛型",
                service().manager().count(), service().manager().config().types.size());
    }
}
