package cn.dreamgary.hubsuite.client;

import cn.dreamgary.hubsuite.rod.AssemblyMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;

/**
 * 客户端侧的注册：菜单类型 / 方块 / 方块物品 / 方块实体类型。
 *
 * <p>这些注册表**不参与网络同步**，所以两端必须各注册一份同 id 的
 * （Fabric 的注册表同步会在缺条目时直接断开连接，见 AGENT.md 坑表）。
 * 界面本身不在这里：客户端用 {@code UiLayouts} 的布局做**通用渲染**。
 */
public final class ClientMenuTypes {

    private ClientMenuTypes() {
    }

    public static void register() {
        AssemblyMenu.registerType();
        // 客户端只需要"能渲染"，所以方块是普通方块（服务端那份是 BaseEntityBlock，带方块实体）
        var block = net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                cn.dreamgary.hubsuite.rod.AssemblyTableIds.ID,
                new net.minecraft.world.level.block.Block(cn.dreamgary.hubsuite.rod.AssemblyTableIds.blockProperties()));
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
                cn.dreamgary.hubsuite.rod.AssemblyTableIds.ID,
                new net.minecraft.world.item.BlockItem(block, cn.dreamgary.hubsuite.rod.AssemblyTableIds.itemProperties()));
        // 方块实体类型也必须注册：Fabric 的注册表同步会因为客户端缺 hubsuite 条目**直接断开连接**（实测）
        cn.dreamgary.hubsuite.rod.AssemblyBlockEntities.register(block);
        HubSuiteClient.LOGGER.info("已注册装配台菜单类型（{}）与方块", AssemblyMenu.ID);
    }
}
