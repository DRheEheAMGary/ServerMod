package cn.dreamgary.hubsuite.client.mixin;

import cn.dreamgary.hubsuite.client.AssemblyScreen;
import cn.dreamgary.hubsuite.rod.AssemblyMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让本模组的菜单能开出**自己的界面**。
 *
 * <p>26.1 把 {@code MenuScreens} 私有化了：{@code register} 是 private、
 * {@code ScreenConstructor} 是包私有，而且 Fabric API 也没有替代入口
 * （fabric-menu-api-v1 / fabric-screen-handler-api-v1 里都没有注册界面的方法）。
 * 所以这里在 {@code MenuScreens.create} 的**头部**拦一下：是装配台就自己建界面并取消原流程。
 */
@Mixin(MenuScreens.class)
public class MenuScreensMixin {

    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void hubsuite$assemblyScreen(MenuType<?> type, Minecraft client, int containerId,
                                                Component title, CallbackInfo ci) {
        if (type != cn.dreamgary.hubsuite.client.ClientMenuTypes.assemblyType()
                || client.player == null) {
            return;
        }
        AssemblyMenu menu = new AssemblyMenu(containerId, client.player.getInventory());
        client.player.containerMenu = menu;
        client.setScreen(new AssemblyScreen(menu, client.player.getInventory(), title));
        ci.cancel();
    }
}
