package cn.dreamgary.hubsuite.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelData;

/**
 * 每个子服的重生点管理。
 *
 * <p><b>为什么需要它：</b>原版玩家的重生点是**全局一份**的
 * （{@code ServerPlayer.respawnConfig} 里只带一个维度 key）。而本模组把三个子服
 * 做成了同一进程内的不同维度，于是玩家在生存服摔死后，原版会去全局重生点里找维度 ——
 * 实测出现过"在生存服摔死，结果重生到了空岛服"。
 *
 * <p>做法：玩家进入某个场所时，把他的重生点显式设成**这个场所**的出生点。
 * 这样死在哪就在哪重生。
 *
 * <p>不覆盖玩家自己设的床/重生锚 —— 如果 {@code respawnConfig} 已经有值且不是我们
 * 设的"强制点"，就保持不动。
 */
public final class RespawnPoints {

    private RespawnPoints() {
    }

    /**
     * 把玩家的重生点设到该场所的出生点。
     *
     * @param force 是否覆盖已有的床/重生锚。默认 false：玩家自己设过的床优先。
     */
    public static void bind(ServerPlayer player, PlayableWorld world, boolean force) {
        bind(player, world, force, null);
    }

    /**
     * @param customSpawn 该玩家在这个场所的实际出生点（例如他自己的空岛）；
     *                    传 null 表示用场所的默认出生点
     */
    public static void bind(ServerPlayer player, PlayableWorld world, boolean force,
                           PlayableWorld.SpawnPoint customSpawn) {
        bind(player, world, force, customSpawn, null);
    }

    /**
     * 绑定重生点。
     *
     * @param level 玩家**当前所在的维度**。多维度子服必须传它 ——
     *              {@link PlayableWorld#level()} 对 {@code SubServer} 恒返回
     *              **主维度**，而空岛服的主维度是那个虚空世界。
     *              不传的话，在经典空岛/海岛死掉会被重生到虚空里，
     *              一直往下掉（用户实测："kill 一下自己就卡住了"）。
     */
    public static void bind(ServerPlayer player, PlayableWorld world, boolean force,
                            PlayableWorld.SpawnPoint customSpawn,
                            net.minecraft.server.level.ServerLevel level) {
        try {
            var current = player.getRespawnConfig();
            if (!force && current != null && !current.forced() && current.respawnData() != null
                    && !isOurSpawn(current.respawnData(), world)) {
                // 玩家自己设过床/重生锚 → 尊重他的选择，不覆盖
                return;
            }

            PlayableWorld.SpawnPoint spawn = customSpawn != null ? customSpawn : world.spawn();
            BlockPos pos = BlockPos.containing(spawn.x(), spawn.y(), spawn.z());
            // 维度优先用调用方给的（玩家真实所在维度），拿不到才退回场所的主维度
            var dimension = level != null ? level.dimension() : world.level().dimension();
            LevelData.RespawnData data = LevelData.RespawnData.of(
                    dimension, pos, spawn.yaw(), spawn.pitch());

            if (isOurSpawn(data, world) && current != null && current.isSamePosition(
                    new ServerPlayer.RespawnConfig(data, false))) {
                return;   // 已经是目标值
            }
            var config = new ServerPlayer.RespawnConfig(data, false);
            if (current != null && current.isSamePosition(config)) {
                return;   // 已经是目标值，不用重复设置（避免多余的客户端同步）
            }
            player.setRespawnPosition(config, false);
        } catch (Throwable t) {
            cn.dreamgary.hubsuite.HubSuite.logger().debug("设置重生点失败：{}", t.toString());
        }
    }

    /** 判断某个重生点是否就是我们给这个场所设的那个。 */
    private static boolean isOurSpawn(LevelData.RespawnData data, PlayableWorld world) {
        if (data.dimension() == null || !data.dimension().equals(world.level().dimension())) {
            return false;
        }
        PlayableWorld.SpawnPoint spawn = world.spawn();
        BlockPos expected = BlockPos.containing(spawn.x(), spawn.y(), spawn.z());
        return data.pos() != null && data.pos().equals(expected);
    }
}
