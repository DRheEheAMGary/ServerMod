package cn.dreamgary.hubsuite.mixin;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修掉原版 {@code DistanceManager.removePlayer} 的空指针。
 *
 * <p><b>问题：</b>原版实现是
 * <pre>
 *   ObjectSet&lt;ServerPlayer&gt; chunkPlayers = this.playersPerChunk.get(chunkPos);
 *   chunkPlayers.remove(player);          // ← 没有判空
 * </pre>
 * 而 {@code addPlayer} 用 {@code computeIfAbsent} 才建表，且只在特定路径下被调用。
 * 于是"移除一个从未登记过的区块"就会抛
 * {@code NullPointerException: ... because "chunkPlayers" is null}。
 *
 * <p><b>为什么会踩到：</b>玩家跨维度传送时原版会先把玩家从旧维度移除
 * （{@code ServerLevel.removePlayerImmediately} → {@code ChunkMap.removeEntity}
 * → {@code DistanceManager.removePlayer}）。一旦该玩家的 {@code lastSectionPos}
 * 指向没登记过的区块，整个传送就抛异常中断 —— 表现为"点进服务器没反应"。
 *
 * <p><b>修法：</b>方法头部检查该区块是否登记过；没登记就直接返回，
 * 语义等价于"本来就没有需要移除的记录"。纯空值防护，不改动正常路径。
 */
@Mixin(DistanceManager.class)
public abstract class DistanceManagerRemovePlayerMixin {

    @Inject(method = "removePlayer", at = @At("HEAD"), cancellable = true)
    private void hubsuite$guardNullChunkPlayers(SectionPos pos, ServerPlayer player, CallbackInfo ci) {
        if (pos == null) {
            ci.cancel();
            return;
        }
        var map = ((DistanceManagerAccessor) (Object) this).hubsuite$playersPerChunk();
        if (map == null || map.get(pos.chunk().pack()) == null) {
            cn.dreamgary.hubsuite.HubSuite.logger().debug(
                    "跳过未登记区块的玩家移除（玩家={}）",
                    player == null ? "?" : player.getName().getString());
            ci.cancel();
        }
    }
}
