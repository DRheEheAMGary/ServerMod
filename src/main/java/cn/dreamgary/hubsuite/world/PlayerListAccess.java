package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.mixin.PlayerListAccessor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

/** 访问 {@code PlayerList} 内部保存方法的唯一出口。 */
public final class PlayerListAccess {

    private PlayerListAccess() {
    }

    /** 走原版路径保存某个玩家的数据（玩家数据会落到他当前所在维度的存档目录）。 */
    public static void save(PlayerList list, ServerPlayer player) {
        ((PlayerListAccessor) list).hubsuite$save(player);
    }
}
