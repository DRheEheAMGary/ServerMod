package cn.dreamgary.hubsuite.client.mixin;

import cn.dreamgary.hubsuite.client.HubSuiteContainerScreen;
import cn.dreamgary.hubsuite.ui.UiLayout;
import cn.dreamgary.hubsuite.ui.UiLayouts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让本模组的菜单能开出界面 —— 而且是**通用**界面：只要菜单类型在
 * {@link UiLayouts} 里有布局，就用 {@link HubSuiteContainerScreen} 渲染，
 * 客户端不需要为每个台子单独写 Screen。
 *
 * <p>26.1 把 {@code MenuScreens} 私有化了：{@code register} 是 private、
 * {@code ScreenConstructor} 是包私有，Fabric API 也没有替代入口
 * （fabric-menu-api-v1 / fabric-screen-handler-api-v1 里都没有注册界面的方法）。
 * 所以这里在 {@code MenuScreens.create} 的**头部**拦一下：有布局就自己建界面并取消原流程。
 */
@Mixin(MenuScreens.class)
public class MenuScreensMixin {

    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void hubsuite$layoutScreen(MenuType<?> type, Minecraft client, int containerId,
                                              Component title, CallbackInfo ci) {
        if (client.player == null) {
            return;
        }
        Identifier menuId = BuiltInRegistries.MENU.getKey(type);
        UiLayout layout = UiLayouts.forMenu(menuId);
        if (layout == null) {
            return;   // 没登记的菜单 → 交回原版
        }
        AbstractContainerMenu menu = type.create(containerId, client.player.getInventory());
        client.player.containerMenu = menu;
        client.setScreen(new HubSuiteContainerScreen<>(menu, client.player.getInventory(), title, layout));
        ci.cancel();
    }
}
