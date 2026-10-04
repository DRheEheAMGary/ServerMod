package cn.dreamgary.hubsuite.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code PlayerList.save(ServerPlayer)} 是 {@code protected}。
 *
 * <p>自检需要走"和正常下线完全一样"的保存路径，才能证明玩家数据确实
 * 被写进了当前子服的存档目录，而不是我们自己另写一套逻辑。
 */
@Mixin(PlayerList.class)
public interface PlayerListAccessor {

    @Invoker("save")
    void hubsuite$save(ServerPlayer player);
}
