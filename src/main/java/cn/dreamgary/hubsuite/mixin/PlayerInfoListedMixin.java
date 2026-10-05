package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让假玩家不出现在客户端 **Tab 玩家列表**（但实体照常显示）。
 *
 * <p><b>真正的根因（看字节码确认）：</b>原版
 * {@code ClientboundPlayerInfoUpdatePacket.Entry(ServerPlayer)} 构造器里，
 * {@code listed} 字段是**硬编码 true**：
 * <pre>
 *   iconst_1                 // listed = true  ← 写死，根本不读 allowsListing()
 *   aload_1
 *   getfield ServerPlayer.connection
 *   ...
 * </pre>
 * 所以 {@code ClientInformation.allowsListing=false} 对最初的 ADD_PLAYER 包
 * **完全没有作用**，客户端收到的永远是"这个玩家要列进 Tab"。
 *
 * <p><b>为什么不能把条目整个剔掉：</b>客户端的 {@code PlayerInfo} 是创建玩家实体的前提
 * （{@code playerInfoMap} 里没有对应 UUID 就不会生成实体）。删掉条目会让
 * NPC **整个消失**（实测踩过）。
 *
 * <p><b>做法：</b>构造完成后，把 {@code listed} 改成 {@code player.allowsListing()}。
 * 客户端 Tab 用 {@code getListedOnlinePlayers()} 渲染，会跳过 {@code listed=false}；
 * 而 {@code playerInfoMap} 里仍有记录，实体正常生成。
 */
@Mixin(ClientboundPlayerInfoUpdatePacket.Entry.class)
public abstract class PlayerInfoListedMixin {

    @Shadow
    @Final
    @Mutable
    private boolean listed;

    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At("RETURN"))
    private void hubsuite$applyAllowsListing(ServerPlayer source, CallbackInfo ci) {
        try {
            if (!source.allowsListing()) {
                this.listed = false;
                // 降到 debug：原版每 600 tick（30 秒）会广播一次 UPDATE_LATENCY，
                // 每个假人都会再走一遍这里 —— INFO 级别会持续刷屏。
                HubSuite.logger().debug("已把 {} 标记为不列入 Tab（listed=false）",
                        source.getName().getString());
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("设置 listed 失败：{}", t.toString());
        }
    }
}
