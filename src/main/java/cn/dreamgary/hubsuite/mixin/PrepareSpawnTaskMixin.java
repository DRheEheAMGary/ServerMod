package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让回到服务器的玩家**直接在大厅落地**，而不是先落在主世界再被传送。
 *
 * <p><b>为什么需要：</b>原版 {@code PrepareSpawnTask.start()} 这样决定登录维度：
 * <pre>
 *   loadedPosition.dimension()          // 玩家存档里记录的维度
 *       .map(server::getLevel)
 *       .orElseGet(() -> {              // ← 没有记录时落到主世界
 *           ServerLevel l = server.getLevel(respawnData.dimension());
 *           return l != null ? l : server.overworld();
 *       });
 * </pre>
 * 而 {@code ClientboundLoginPacket} 里的出生信息就是在这一步定下的。
 * 于是"进主世界 → 再被 mod 传送到大厅"会让客户端**连续加载两个世界** ——
 * 实测表现为"退出游戏后重连很慢"。
 *
 * <p><b>修法：</b>拦截 {@code orElseGet} 的返回值，换成大厅。
 * 玩家只加载一个世界，重连立刻完成。玩家数据仍在进入大厅时由
 * {@code PlayerDataRouter} 从对应子服的存档读写，背包/经验不受影响。
 *
 * <p>用 {@code @ModifyExpressionValue} 精确拦截那一次 {@code orElseGet}，
 * 比按局部变量槽位（ordinal）更稳 —— 后者会随版本改动错位。
 */
@Mixin(net.minecraft.server.network.config.PrepareSpawnTask.class)
public abstract class PrepareSpawnTaskMixin {

    // ordinal = 0 是**必须的**：start() 里有两处 orElseGet，
    // 第 1 处（offset 105）取登录维度（返回 ServerLevel），
    // 第 2 处（offset 135）取出生坐标（返回 CompletableFuture<Vec3>）。
    // 不指定 ordinal 会命中第 2 处，导致
    // "ClassCastException: ServerLevel cannot be cast to CompletableFuture"，
    // 玩家在配置阶段就被断开（实测踩过）。
    @ModifyExpressionValue(
            method = "start",
            at = @At(value = "INVOKE", ordinal = 0,
                    target = "Ljava/util/Optional;orElseGet(Ljava/util/function/Supplier;)Ljava/lang/Object;"))
    private Object hubsuite$redirectSpawnToLobby(Object original) {
        try {
            var worlds = HubSuite.worlds();
            if (worlds == null) {
                return original;
            }
            var lobby = worlds.lobby().orElse(null);
            if (lobby == null) {
                return original;
            }
            ServerLevel lobbyLevel = lobby.level();
            if (original instanceof ServerLevel level && level.dimension().equals(lobbyLevel.dimension())) {
                return original;   // 已经是大厅
            }
            HubSuite.logger().info("登录出生点重定向到大厅（避免多加载一个世界，加快重连）：原={}",
                    original instanceof ServerLevel l ? l.dimension().identifier() : String.valueOf(original));
            return lobbyLevel;
        } catch (Throwable t) {
            HubSuite.logger().warn("登录出生点重定向失败，沿用原版行为：{}", t.toString());
            return original;
        }
    }
}
