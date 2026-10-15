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
        HubSuiteClient.LOGGER.info("已注册装配台菜单类型（{}）", AssemblyMenu.ID);
    }

    public static MenuType<AssemblyMenu> assemblyType() {
        return assembly != null ? assembly : AssemblyMenu.registerType();
    }
}
