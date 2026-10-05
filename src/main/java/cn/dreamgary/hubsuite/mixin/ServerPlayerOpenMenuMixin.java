package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.OptionalInt;

/**
 * 诊断：记录**每一次** {@code openMenu} 调用。
 *
 * <p>用户反馈"右键能放方块、但箱子完全打不开"。放方块走的是 {@code useItemOn} 的
 * 手持物品分支，开箱子走的是空手的 {@code useWithoutItem} 分支 ——
 * 说明数据包、权限、原版闸门全部正常，问题精确地卡在"打开容器"这一步。
 *
 * <p>这条日志是分界线：
 * <ul>
 *   <li>打印了 → 服务端确实调了 openMenu，问题在客户端没有显示界面；</li>
 *   <li>没打印 → 方块自己的 {@code useWithoutItem} 没走到这里，
 *       也就是 {@code getMenuProvider} 返回了 null
 *       （原版在这种情况下会因为"箱子被挡住"而拒绝打开）。</li>
 * </ul>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerOpenMenuMixin {

    @Inject(method = "openMenu(Lnet/minecraft/world/MenuProvider;)Ljava/util/OptionalInt;",
            at = @At("HEAD"))
    private void hubsuite$logOpenMenu(MenuProvider provider,
                                      CallbackInfoReturnable<OptionalInt> cir) {
        try {
            ServerPlayer self = (ServerPlayer) (Object) this;
            HubSuite.logger().info("[交互诊断] openMenu 被调用：{} 提供者 {}",
                    self.getName().getString(),
                    provider == null ? "null" : provider.getClass().getSimpleName());
        } catch (Throwable ignored) {
            // 诊断失败不影响游戏
        }
    }
}
