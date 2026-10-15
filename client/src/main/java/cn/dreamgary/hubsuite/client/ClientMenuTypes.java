package cn.dreamgary.hubsuite.client;

import cn.dreamgary.hubsuite.rod.AssemblyMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.MenuType;

/** 客户端侧的菜单类型（注册表不参与同步，两端各注册一份同 id 的）。 */
public final class ClientMenuTypes {

    private static MenuType<AssemblyMenu> assembly;

    private ClientMenuTypes() {
    }

    public static void register() {
        assembly = AssemblyMenu.registerType();
        // 装配台方块 + 它的物品：方块/物品都不参与网络同步，客户端必须注册同 id 的一份。
        // 服务端那份带 Polymer 与方块实体；客户端只需要"能渲染"，所以是普通方块。
        var block = net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.BLOCK,
                cn.dreamgary.hubsuite.rod.AssemblyTableIds.ID,
                new net.minecraft.world.level.block.Block(cn.dreamgary.hubsuite.rod.AssemblyTableIds.blockProperties()));
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM,
                cn.dreamgary.hubsuite.rod.AssemblyTableIds.ID,
                new net.minecraft.world.item.BlockItem(block, cn.dreamgary.hubsuite.rod.AssemblyTableIds.itemProperties()));
        // 方块实体类型也必须注册：Fabric 的注册表同步会因为客户端缺 hubsuite 条目**直接断开连接**（实测）
        cn.dreamgary.hubsuite.rod.AssemblyBlockEntities.register(block);
        HubSuiteClient.LOGGER.info("已注册装配台菜单类型（{}）", AssemblyMenu.ID);
    }

    public static MenuType<AssemblyMenu> assemblyType() {
        return assembly != null ? assembly : AssemblyMenu.registerType();
    }
}
