package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修「在生存服死掉却重生到创造服」。
 *
 * <p><b>根因（javap 确证）：</b>
 * <pre>
 * TeleportTransition.createDefault / missingRespawnBlock
 *     → player.level().getServer().findRespawnDimension()   ← 用它决定重生去哪个维度
 *
 * MinecraftServer.findRespawnDimension()
 *     0: getWorldData().overworldData().getRespawnData()    ← 读**全局主世界存档数据**
 *    15: .dimension() → getLevel(key) → 拿到就用
 *    34: overworld()  ← 拿不到才兜底
 * </pre>
 *
 * <p>而"每个子服一份独立存档"的实现里，各子服的
 * {@code level.setRespawnData(...)}（{@code SubServer.applySpawn} / {@code Lobby}）
 * 写的是**同一份共享的** {@code worldData().overworldData()} —— 互相覆盖，
 * **最后加载的子服赢**。按配置顺序最后是 {@code creative}，
 * 于是任何"没有床/重生锚"的玩家一死就被送进创造服。
 *
 * <p><b>实测证据</b>（重生解析入口三个输入全对，输出却是创造服）：
 * <pre>
 * [RD2] findRespawn 入口：玩家维度=hubsuite:server_survival
 *       | respawnConfig=hubsuite:server_survival@(0,76,0)
 *       | getLevel(config维度)=hubsuite:server_survival
 *       | 死亡中=true
 * 随后 findRespawnPositionAndUseSpawnBlock 返回
 *   newLevel=hubsuite:server_creative, missingBlock=true
 * </pre>
 *
 * <p><b>修法：</b>兜底分支的语义是"玩家没有床/重生锚"。本模组里那种情况
 * 应该送回**他当前所在的子服**（"死在哪就在哪重生"）—— 由
 * {@code RespawnPoints} 把该子服的出生点绑在玩家的 {@code RespawnConfig} 上，
 * 走的是另一条分支。所以这里只修被那条错误共享数据带偏的兜底结果。
 *
 * <p>坐标也要一起纠正：兜底位置来自"被覆盖的那份共享出生点"，
 * 在别的子服里可能指向虚空（实测空岛服会落到 Y=-111 然后靠虚空救援捞回来）。
 * 所以优先用**该子服自己登记的出生点**，退而用维度自己的 respawnData。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRespawnDimensionMixin {

    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
    private void hubsuite$fixRespawnDimension(boolean bl,
                                              TeleportTransition.PostTeleportTransition post,
                                              CallbackInfoReturnable<TeleportTransition> cir) {
        try {
            TeleportTransition t = cir.getReturnValue();
            if (t == null) {
                return;
            }
            ServerPlayer self = (ServerPlayer) (Object) this;
            ServerLevel here = self.level();
            if (here == null) {
                return;
            }
            ServerLevel target = t.newLevel();
            if (target == null) {
                return;
            }
            boolean sameDim = target.dimension().equals(here.dimension());
            if (sameDim && !t.missingRespawnBlock()) {
                return;   // 正常路径（床/锚），不动
            }
            /*
             * 走到这里有两种情况，都要纠正：
             *   ① 跨维度（兜底读了被覆盖的共享出生点 → 串到别的子服）；
             *   ② 同维度但 missingRespawnBlock（兜底位置可能是虚空）。
             * 位置一律改用"该子服自己登记的出生点"，拿不到才退回维度 respawnData。
             */
            if (!sameDim) {
                HubSuite.logger().info("重生兜底维度已纠正：{} -> {}（共享出生点数据被覆盖导致的串服）",
                        target.dimension().identifier(), here.dimension().identifier());
            }
            var pos = hubsuite$pickSafePos(here, t.position());
            var fixed = new TeleportTransition(here, pos, Vec3.ZERO,
                    t.yRot(), t.xRot(), t.relatives(), t.postTeleportTransition());
            cir.setReturnValue(fixed);
        } catch (Throwable e) {
            // 绝不因为这里出错让玩家重不了生
            HubSuite.logger().debug("纠正重生维度失败，沿用原版：{}", e.toString());
        }
    }

    /**
     * 选一个该维度里"能站人"的重生点。
     *
     * <p>优先级：该子服登记的出生点 → 该维度自己的 respawnData → 原样保留。
     * 前两个都拿不到时不动，避免把玩家送进更糟的位置。
     */
    private static Vec3 hubsuite$pickSafePos(ServerLevel level, Vec3 fallback) {
        try {
            var worlds = HubSuite.worlds();
            if (worlds != null) {
                for (var w : worlds.allWorlds()) {
                    if (w.level() != null && w.level().dimension().equals(level.dimension())) {
                        var sp = w.spawn();
                        return new Vec3(sp.x(), sp.y(), sp.z());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            var rd = level.getRespawnData();
            if (rd != null && rd.pos() != null) {
                var p = rd.pos();
                // 只在它落在世界高度范围内时才信
                if (p.getY() > level.getMinY() + 1 && p.getY() < level.getMaxY()) {
                    return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }
}
