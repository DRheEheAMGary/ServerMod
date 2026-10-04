package cn.dreamgary.hubsuite.command;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.ui.ChestMenuScreen;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /menu} —— 打开**服务器选择页面**（箱子式界面）。
 *
 * <p>与点大厅假人是同一个界面：{@link cn.dreamgary.hubsuite.npc.ServerMenu}。
 * 做成指令是为了两件事：
 * <ul>
 *   <li>假人没刷新出来 / 被挡住时，玩家仍有入口；</li>
 *   <li>给客户端模组屏蔽自定义界面时留个后备通道。</li>
 * </ul>
 */
public final class MenuCommand {

    private MenuCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("menu")
                        .executes(ctx -> open(ctx.getSource()))));
    }

    private static int open(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("\u00A7c这个指令只能由玩家执行。"));
            return 0;
        }
        var worlds = HubSuite.worlds();
        if (worlds == null || !worlds.isReady()) {
            source.sendFailure(Component.literal("\u00A7cHubSuite 尚未就绪。"));
            return 0;
        }
        cn.dreamgary.hubsuite.npc.ServerMenu.open(player, worlds);
        return 1;
    }

    /** 供别处复用：判断某个玩家当前是否开着菜单。 */
    public static boolean hasMenuOpen(ServerPlayer player) {
        return ChestMenuScreen.hasOpen(player.getUUID());
    }
}
