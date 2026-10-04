package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.feature.CommandGuard;
import cn.dreamgary.hubsuite.feature.CommandGuardHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 命令闸门的实际拦截点。
 *
 * <p>Fabric API 只提供聊天消息事件，没有"命令即将执行"事件，
 * 所以只能挂在原版的命令包处理上。两种包都要拦：
 * 未签名命令（{@code ServerboundChatCommandPacket}）与
 * 带签名参数的命令（{@code ServerboundChatCommandSignedPacket}）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerCommandMixin {

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void hubsuite$guardUnsigned(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        if (hubsuite$block(packet.command())) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void hubsuite$guardSigned(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        if (hubsuite$block(packet.command())) {
            ci.cancel();
        }
    }

    private boolean hubsuite$block(String command) {
        CommandGuard guard = CommandGuardHolder.get();
        if (guard == null) {
            return false;
        }
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null) {
            return false;
        }
        try {
            Component denial = guard.check(player, command);
            if (denial != null) {
                player.sendSystemMessage(denial);
                HubSuite.logger().debug("已拦截 {} 的命令：/{}",
                        player.getName().getString(), command.split(" ", 2)[0]);
                return true;
            }
        } catch (Throwable t) {
            HubSuite.logger().error("命令闸门异常，已放行 /{}", command, t);
        }
        return false;
    }
}
