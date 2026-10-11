package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.feature.CommandGuard;
import cn.dreamgary.hubsuite.feature.CommandGuardHolder;
import cn.dreamgary.hubsuite.integration.HomeNamespace;
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
 * 命令的预处理点：命令闸门 + 家命令的**子服命名空间改写**。
 *
 * <p>Fabric API 只提供聊天消息事件，没有"命令即将执行"事件，所以只能挂在
 * 原版的命令包处理上。两种包都要拦：未签名与带签名的命令。
 *
 * <p>改写家命令（{@code /home}、{@code /sethome} 等）时，先把名字加上当前子服前缀
 * （见 {@link HomeNamespace}），然后用改写后的命令**重新派发**并取消原始包 ——
 * 这样玩家输入仍是原名，但存储是分开的。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerCommandMixin {

    @Inject(method = "handleChatCommand", at = @At("HEAD"), cancellable = true)
    private void hubsuite$guardUnsigned(ServerboundChatCommandPacket packet, CallbackInfo ci) {
        hubsuite$preprocess(packet.command(), ci);
    }

    @Inject(method = "handleSignedChatCommand", at = @At("HEAD"), cancellable = true)
    private void hubsuite$guardSigned(ServerboundChatCommandSignedPacket packet, CallbackInfo ci) {
        hubsuite$preprocess(packet.command(), ci);
    }

    private void hubsuite$preprocess(String command, CallbackInfo ci) {
        ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
        if (player == null) {
            return;
        }

        // 1) 命令闸门（按命令名判定；必须先于改写，改写后就不再走这里了）
        CommandGuard guard = CommandGuardHolder.get();
        if (guard != null) {
            try {
                Component denial = guard.check(player, command);
                if (denial != null) {
                    player.sendSystemMessage(denial);
                    HubSuite.logger().debug("已拦截 {} 的命令：/{}",
                            player.getName().getString(), command.split(" ", 2)[0]);
                    ci.cancel();
                    return;
                }
            } catch (Throwable t) {
                // fail-closed：闸门自己出错时必须拒绝
                HubSuite.logger().error("命令闸门异常，已按拒绝处理 /{}", command, t);
                player.sendSystemMessage(Component.literal(
                        "\u00A7c指令校验出错，已阻止执行，请联系管理员查看控制台。"));
                ci.cancel();
                return;
            }
        }

        // 2) 家命令：加子服前缀后重新派发
        try {
            String rewritten = HomeNamespace.rewrite(player, command);
            if (rewritten != null && !rewritten.equals(command)) {
                var server = player.level().getServer();
                if (server != null) {
                    server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), rewritten);
                }
                ci.cancel();
            }
        } catch (Throwable t) {
            HubSuite.logger().error("改写家命令失败 /{}", command, t);
        }
    }
}
