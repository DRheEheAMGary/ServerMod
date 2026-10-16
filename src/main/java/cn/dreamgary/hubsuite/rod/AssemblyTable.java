package cn.dreamgary.hubsuite.rod;

import cn.dreamgary.hubsuite.HubSuite;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

/**
 * 装配台的**服务端入口**。
 *
 * <p>0.5.0 先不方块化：菜单与界面（客户端）先做出来，用管理员命令 {@code /rod} 打开验证。
 * 0.6 会把这里换成"方块 + 方块实体"（见 docs/钓竿系统方案.md 第 3 节）。
 */
public final class AssemblyTable {

    private AssemblyTable() {
    }

    public static void register() {
        AssemblyMenu.registerType();
        // 方块 + 方块实体（两端都要注册；客户端那份在 ClientMenuTypes 里）
        AssemblyTableBlock.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(Commands.literal("rod")
                        .requires(source -> {
                            if (source.getPlayer() instanceof ServerPlayer player) {
                                var permissions = HubSuite.permissions();
                                return permissions != null && permissions.hasAdminPermission(player);
                            }
                            return true;   // 控制台 / RCON
                        })
                        .executes(context -> {
                            if (context.getSource().getPlayer() instanceof ServerPlayer player) {
                                open(player);
                                return 1;
                            }
                            context.getSource().sendFailure(Component.literal("该命令只能由玩家执行。"));
                            return 0;
                        })
                        .then(Commands.literal("demo").executes(context -> {
                            if (context.getSource().getPlayer() instanceof ServerPlayer player) {
                                open(player, demoParts());
                                return 1;
                            }
                            context.getSource().sendFailure(Component.literal("该命令只能由玩家执行。"));
                            return 0;
                        }))));
        HubSuite.logger().info("装配台菜单已注册（暂时用 /rod 打开，0.6 换成方块）");
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, p) -> new AssemblyMenu(containerId, inventory),
                Component.literal("钓竿装配台")));
    }

    /** 开发用（{@code /rod demo}）：预填一根竿 + 线 + 钩，方便肉眼核对槽位有没有钉在竿上。 */
    public static void open(ServerPlayer player, net.minecraft.world.Container parts) {
        player.openMenu(new SimpleMenuProvider(
                (containerId, inventory, p) -> new AssemblyMenu(containerId, inventory, parts),
                Component.literal("钓竿装配台（演示）")));
    }

    private static net.minecraft.world.Container demoParts() {
        var parts = new net.minecraft.world.SimpleContainer(AssemblyBlockEntity.SIZE);
        var rod = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FISHING_ROD);
        cn.dreamgary.hubsuite.rod.RodData.defaults().writeTo(rod);
        parts.setItem(AssemblyMenu.SLOT_ROD, rod);
        parts.setItem(AssemblyMenu.SLOT_LINE,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STRING));
        parts.setItem(AssemblyMenu.SLOT_HOOK,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OBSIDIAN));
        return parts;
    }
}
