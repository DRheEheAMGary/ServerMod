package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.ui.ChestMenuScreen;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接管箱子式菜单的点击。
 *
 * <p>箱子界面默认允许把物品拿走。这里在 {@code handleContainerClick} 的
 * **最前面**就把包截下来交给 {@link ChestMenuScreen}，
 * 原版逻辑一点都不执行 —— 所以物品既不会被拿走，
 * 也不会因为 shift 点击、拖拽、数字键交换产生副作用。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMenuMixin {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void hubsuite$handleMenuClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (player == null) {
            return;
        }
        if (ChestMenuScreen.handleClick(player, packet.containerId(), packet.slotNum())) {
            ci.cancel();
        }
    }
}
