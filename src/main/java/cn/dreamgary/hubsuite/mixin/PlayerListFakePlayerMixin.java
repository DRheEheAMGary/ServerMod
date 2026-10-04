package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.fake.HubSuiteFakePlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 把假玩家从"在线人数"与"在线列表"里剔除。
 *
 * <p>假玩家是真正的 {@code ServerPlayer}，不处理的话服务器状态栏、{@code /list}、
 * 以及任何基于 {@code PlayerList.getPlayers()} 的统计都会把 NPC 算成玩家
 * （实测出现过"7 人在线，其中 3 个是 NPC"）。
 *
 * <p>{@code allowsListing=false} 只让 NPC 不出现在客户端 Tab 列表里，
 * 管不到服务端计数，所以必须在服务端过滤。
 *
 * <p>计数时直读底层 {@code players} 字段，避免与 {@code getPlayers()} 的改写互相递归。
 */
@Mixin(PlayerList.class)
public abstract class PlayerListFakePlayerMixin {

    /** 在线人数不含假玩家。 */
    @Inject(method = "getPlayerCount", at = @At("RETURN"), cancellable = true)
    private void hubsuite$excludeFakeFromCount(CallbackInfoReturnable<Integer> cir) {
        int fake = hubsuite$countFake();
        if (fake > 0) {
            cir.setReturnValue(Math.max(0, cir.getReturnValueI() - fake));
        }
    }

    /** 在线名单不含假玩家。 */
    @Inject(method = "getPlayers", at = @At("RETURN"), cancellable = true)
    private void hubsuite$excludeFakeFromList(CallbackInfoReturnable<List<ServerPlayer>> cir) {
        List<ServerPlayer> all = cir.getReturnValue();
        if (all == null || all.isEmpty()) {
            return;
        }
        List<ServerPlayer> real = new ArrayList<>(all.size());
        for (ServerPlayer player : all) {
            if (!(player instanceof HubSuiteFakePlayer)) {
                real.add(player);
            }
        }
        if (real.size() != all.size()) {
            cir.setReturnValue(real);
        }
    }

    private int hubsuite$countFake() {
        int count = 0;
        List<ServerPlayer> raw = ((PlayerListFieldAccessor) (Object) this).hubsuite$rawPlayers();
        if (raw == null) {
            return 0;
        }
        for (ServerPlayer player : raw) {
            if (player instanceof HubSuiteFakePlayer) {
                count++;
            }
        }
        return count;
    }
}
