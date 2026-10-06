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
        if (player instanceof ServerPlayer sp) {
            AuxDataRouter.logResolvedDirs(sp, sp.level().dimension());
        }
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
        AuxDataRouter.logResolvedDirs(player, player == null ? null : player.level().dimension());
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
    public int hubsuite$flushAux(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        UUID uuid = player.getUUID();
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
        /*
         * 注意：**这里不换玩家身上的引用**。
         *
         * 本方法在"切服流程"里是在**传送之前**调用的，此刻 player.level() 还是旧维度，
         * 立刻重建只会把新对象又绑到刚清空的那条旧路径上。换引用交给
         * {@link #hubsuite$rebindAux}，由 PlayerRouter 在传送完成后调用。
         */
        return handled;
    }

    @Override
    public int hubsuite$rebindAux(ServerPlayer player) {
        if (player == null) {
            return 0;
        }
        int swapped = 0;
        PlayerList self = (PlayerList) (Object) this;
        /*
         * 关键一步：把玩家自己身上那份 final 引用也换掉。
         *
         * getStats() / getAdvancements() 的字节码就是"读字段并返回"（javap 已确认），
         * 所以只清 PlayerList 的两张 map 时，玩家仍拿着旧对象 —— 而旧对象的
         * 文件路径是构造时烧死的。换维度时玩家对象是复用的，于是照旧读写旧子服目录。
         *
         * 此刻玩家已在目标维度，新对象会绑到正确目录。
         */
        try {
            ServerStatsCounter freshStats = self.getPlayerStats(player);
            if (swapField(player, "stats", ServerStatsCounter.class, freshStats)) {
                swapped++;
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("换掉玩家身上的统计引用失败：{}", t.toString());
        }
        try {
            PlayerAdvancements freshAdv = self.getPlayerAdvancements(player);
            if (swapField(player, "advancements", PlayerAdvancements.class, freshAdv)) {
                swapped++;
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("换掉玩家身上的成就引用失败：{}", t.toString());
        }
        return swapped;
    }

    /**
     * 反射把 {@code ServerPlayer} 里的 final 字段换成新对象。
     *
     * <p>为什么必须反射：那两个字段是 {@code private final}，原版没有 setter，
     * 而"换掉引用"正是隔离能否成立的前提。
     *
     * @return 是否真的换了（字段不存在/已经相同/失败都返回 false，只记日志）
     */
    private static <T> boolean swapField(ServerPlayer player, String fieldName,
                                         Class<T> type, T fresh) {
        if (player == null || fresh == null) {
            return false;
        }
        try {
            java.lang.reflect.Field f = ServerPlayer.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object old = f.get(player);
            if (old == fresh) {
                return false;   // 已经是对的，不用动
            }
            f.set(player, fresh);
            return true;
        } catch (Throwable t) {
            HubSuite.logger().warn("反射替换 ServerPlayer.{} 失败：{}", fieldName, t.toString());
            return false;
        }
    }

    @Override
    public boolean hubsuite$debugHasAdvancements(UUID uuid) {
        return uuid != null && this.advancements.containsKey(uuid);
    }

    @Override
    public boolean hubsuite$debugHasStats(UUID uuid) {
        return uuid != null && this.stats.containsKey(uuid);
    }
}
