package cn.dreamgary.hubsuite.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 直读 {@code PlayerList.players} 原始字段。
 *
 * <p>{@code PlayerListFakePlayerMixin} 会改写 {@code getPlayers()} 的返回值，
 * 如果过滤逻辑再通过 {@code getPlayers()} 去数假玩家就会互相递归。
 * 因此这里直接读底层字段。
 */
@Mixin(PlayerList.class)
public interface PlayerListFieldAccessor {

    @Accessor("players")
    List<ServerPlayer> hubsuite$rawPlayers();
}
