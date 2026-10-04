package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.ui.DialogRouter;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接收对话框按钮点击与表单提交。
 *
 * <p><b>为什么必须 Mixin：</b>26.1 的对话框输入值通过
 * {@code ServerboundCustomClickActionPacket} 回传，而原版
 * {@code MinecraftServer.handleCustomClickAction} 的方法体只有一行 debug 日志
 * （已反编译确认），Fabric API 也没有对应事件。整条链路
 * 「客户端填表 → NBT 负载 → 服务端路由」必须由本模组在服务端接住。
 *
 * <p>安全性：这条路径不经过聊天、不经过命令、不写日志，
 * 因此密码不会出现在任何日志文件里。
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonPacketListenerMixin implements ServerCommonPacketListener {

    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void hubsuite$routeDialogAction(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
        try {
            if (DialogRouter.route(this, packet)) {
                // 已被我们处理：不让原版再打一遍 debug 日志
                ci.cancel();
            }
        } catch (Throwable t) {
            HubSuite.logger().error("处理对话框动作 {} 时出错", packet.id(), t);
        }
    }
}
