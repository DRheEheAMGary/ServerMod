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

    /**
     * 诊断：记录**每一个**右键方块数据包。
     *
     * <p>放在最源头（数据包入口），因此能一次性区分：
     * <ul>
     *   <li>日志出现 → 客户端确实发了包，问题在服务端后续处理；</li>
     *   <li>日志不出现 → 客户端压根没发包，问题在客户端/交互前置条件。</li>
     * </ul>
     * 用户反馈"箱子打不开"但模组回调没有任何输出时，这是唯一能定位的办法。
     */
    @Inject(method = "handleUseItemOn", at = @At("HEAD"))
    private void hubsuite$logUseItemOn(
            net.minecraft.network.protocol.game.ServerboundUseItemOnPacket packet,
            CallbackInfo ci) {
        if (player == null) {
            return;
        }
        try {
            var pos = packet.getHitResult().getBlockPos();
            var state = player.level().getBlockState(pos);
            cn.dreamgary.hubsuite.HubSuite.logger().info(
                    "[交互诊断] 收到右键方块包：{} 目标 {} {} 维度 {}｜手持 {}｜"
                            + "客户端已加载={}｜等待重生={}",
                    player.getName().getString(),
                    state.getBlock().getName().getString(), pos,
                    player.level().dimension().identifier(),
                    player.getItemInHand(packet.getHand()).getItem(),
                    hubsuite$clientLoaded(),
                    hubsuite$waitingForRespawn());
        } catch (Throwable t) {
            cn.dreamgary.hubsuite.HubSuite.logger().warn("[交互诊断] 记录失败：{}", t.toString());
        }
    }

    /**
     * 诊断：右键**空气**（而不是方块）。
     *
     * <p>如果玩家明明对着箱子右键、服务端却收到这个包而不是
     * {@code UseItemOn}，说明**客户端认为那里没有方块** ——
     * 那就不是交互逻辑的问题，而是区块数据在客户端不同步。
     */
    @Inject(method = "handleUseItem", at = @At("HEAD"))
    private void hubsuite$logUseItem(
            net.minecraft.network.protocol.game.ServerboundUseItemPacket packet,
            CallbackInfo ci) {
        if (player == null) {
            return;
        }
        try {
            cn.dreamgary.hubsuite.HubSuite.logger().info(
                    "[交互诊断] 收到【右键空气】包：{} 维度 {}｜视角方向 {}｜手上 {}",
                    player.getName().getString(),
                    player.level().dimension().identifier(),
                    packet.getYRot() + "/" + packet.getXRot(),
                    player.getItemInHand(packet.getHand()).getItem(),
                    hubsuite$clientLoaded(),
                    hubsuite$waitingForRespawn());
        } catch (Throwable ignored) {
            // 诊断失败不影响游戏
        }
    }

    /** 诊断：左键（挖掘 / 攻击）。用来确认客户端的手部动作是正常的。 */
    @Inject(method = "handlePlayerAction", at = @At("HEAD"))
    private void hubsuite$logPlayerAction(
            net.minecraft.network.protocol.game.ServerboundPlayerActionPacket packet,
            CallbackInfo ci) {
        if (player == null) {
            return;
        }
        try {
            cn.dreamgary.hubsuite.HubSuite.logger().info(
                    "[交互诊断] 收到【左键/挖掘】包：{} 动作 {} 目标 {} 维度 {}",
                    player.getName().getString(), packet.getAction(),
                    packet.getPos(), player.level().dimension().identifier());
        } catch (Throwable ignored) {
            // 诊断失败不影响游戏
        }
    }

    /** 诊断：容器关闭包（确认客户端有没有"以为开着界面"）。 */
    @Inject(method = "handleContainerClose", at = @At("HEAD"))
    private void hubsuite$logContainerClose(
            net.minecraft.network.protocol.game.ServerboundContainerClosePacket packet,
            CallbackInfo ci) {
        if (player == null) {
            return;
        }
        try {
            cn.dreamgary.hubsuite.HubSuite.logger().info(
                    "[交互诊断] 收到【关闭容器】包：{} containerId={}｜服务端当前菜单 {}(containerId={})",
                    player.getName().getString(), packet.getContainerId(),
                    player.containerMenu.getClass().getSimpleName(),
                    player.containerMenu.containerId);
        } catch (Throwable ignored) {
            // 诊断失败不影响游戏
        }
    }

    /**
     * 读原版的"客户端是否已完成加载"闸门（诊断用，反射读取）。
     *
     * <p>原版 {@code handleUseItemOn} 第一行就是
     * {@code if (!this.hasClientLoaded()) return;}，而它要求
     * {@code !waitingForRespawn && clientLoadedTimeoutTimer <= 0}。
     * 任何一条不满足，右键方块都会被**静默忽略** —— 日志里一条错误都没有，
     * 表现就是用户说的"右键完全没有任何反应"。
     */
    private boolean hubsuite$clientLoaded() {
        if (player == null || player.connection == null) {
            return false;
        }
        try {
            var m = player.connection.getClass()
                    .getMethod("hasClientLoaded");
            return (Boolean) m.invoke(player.connection);
        } catch (Throwable t) {
            return true;   // 读不到就当正常，别误导
        }
    }

    /** 读原版的 waitingForRespawn 字段（诊断用）。 */
    private boolean hubsuite$waitingForRespawn() {
        if (player == null || player.connection == null) {
            return false;
        }
        try {
            var f = player.connection.getClass()
                    .getDeclaredField("waitingForRespawn");
            f.setAccessible(true);
            return f.getBoolean(player.connection);
        } catch (Throwable t) {
            return false;
        }
    }

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
