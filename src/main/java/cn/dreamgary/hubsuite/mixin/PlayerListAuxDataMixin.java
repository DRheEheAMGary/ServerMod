package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.AuxDataRouter;
import cn.dreamgary.hubsuite.world.PlayerListAuxAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 让**成就与统计**也按子服维度隔离。
 *
 * <p>{@code PlayerListStorageMixin} 只接管了 {@code playerIo}（背包/经验/末影箱），
 * 而原版把成就和统计**另存两份文件**，路径来自
 * {@code MinecraftServer.getWorldPath(PLAYER_STATS_DIR / PLAYER_ADVANCEMENTS_DIR)}
 * —— 与玩家所在维度无关，全部落在主世界。于是"只在生存服该拿的成就，
 * 在大厅/创造服也会解锁并互相污染"，与"子服完全独立"的承诺不符。
 *
 * <p>两个改造点：
 * <ol>
 *   <li>把这两处 {@code getWorldPath} 的返回值按**玩家当前维度**重定向到
 *       对应子服存档的 {@code players/stats|advancements}；</li>
 *   <li>提供 {@link PlayerListAuxAccess#hubsuite$flushAux} 用于切换维度时
 *       把旧维度那份存盘并从缓存移除（否则缓存对象里烧死的路径会一直生效）。</li>
 * </ol>
 */
@Mixin(PlayerList.class)
public abstract class PlayerListAuxDataMixin implements PlayerListAuxAccess {

    @Shadow
    @Final
    private Map<UUID, ServerStatsCounter> stats;

    @Shadow
    @Final
    private Map<UUID, PlayerAdvancements> advancements;

    /** 统计：把目录换成当前维度所属子服的 players/stats。 */
    @ModifyExpressionValue(
            method = "locateStatsFile",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getWorldPath(Lnet/minecraft/world/level/storage/LevelResource;)Ljava/nio/file/Path;"))
    private Path hubsuite$routeStatsDir(Path original) {
        try {
            Path routed = AuxDataRouter.statsDirForContext();
            return routed != null ? routed : original;
        } catch (Throwable t) {
            HubSuite.logger().warn("重定向统计目录失败，沿用原版路径：{}", t.toString());
            return original;
        }
    }

    /** 统计：进入解析前登记"当前是哪个玩家"。 */
    @Inject(method = "getPlayerStats", at = @At("HEAD"))
    private void hubsuite$enterStats(Player player, CallbackInfoReturnable<ServerStatsCounter> cir) {
        // 传**维度**而不是 UUID：玩家加入过程中还没进玩家列表，
        // 靠 UUID 反查会查不到，导致重定向失效、数据落回全局路径。
        AuxDataRouter.setContext(player == null ? null : player.level().dimension());
    }

    @Inject(method = "getPlayerStats", at = @At("RETURN"))
    private void hubsuite$exitStats(Player player, CallbackInfoReturnable<ServerStatsCounter> cir) {
        AuxDataRouter.clearContext();
    }

    /** 成就：同样把目录换掉。这个方法本身拿得到玩家，直接设上下文即可。 */
    @Inject(method = "getPlayerAdvancements", at = @At("HEAD"))
    private void hubsuite$enterAdvancements(ServerPlayer player,
                                            CallbackInfoReturnable<PlayerAdvancements> cir) {
        // 传**维度**而不是 UUID：玩家加入过程中还没进玩家列表，
        // 靠 UUID 反查会查不到，导致重定向失效、数据落回全局路径。
        AuxDataRouter.setContext(player == null ? null : player.level().dimension());
    }

    @Inject(method = "getPlayerAdvancements", at = @At("RETURN"))
    private void hubsuite$exitAdvancements(ServerPlayer player,
                                           CallbackInfoReturnable<PlayerAdvancements> cir) {
        AuxDataRouter.clearContext();
    }

    @ModifyExpressionValue(
            method = "getPlayerAdvancements",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/MinecraftServer;getWorldPath(Lnet/minecraft/world/level/storage/LevelResource;)Ljava/nio/file/Path;"))
    private Path hubsuite$routeAdvancementsDir(Path original) {
        try {
            Path routed = AuxDataRouter.advancementsDirForContext();
            return routed != null ? routed : original;
        } catch (Throwable t) {
            HubSuite.logger().warn("重定向成就目录失败，沿用原版路径：{}", t.toString());
            return original;
        }
    }

    @Override
    public int hubsuite$flushAux(UUID uuid) {
        if (uuid == null) {
            return 0;
        }
        int handled = 0;
        try {
            ServerStatsCounter counter = this.stats.remove(uuid);
            if (counter != null) {
                counter.save();
                handled++;
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("落盘统计失败：{}", t.toString());
        }
        try {
            PlayerAdvancements adv = this.advancements.remove(uuid);
            if (adv != null) {
                adv.save();
                handled++;
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("落盘成就失败：{}", t.toString());
        }
        return handled;
    }
}
