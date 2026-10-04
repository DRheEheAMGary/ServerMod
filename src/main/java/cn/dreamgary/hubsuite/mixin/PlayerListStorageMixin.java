package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.world.PlayerDataRouter;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * 把玩家数据的读写路由到"玩家当前所属子服"的存储。
 *
 * <p>{@code PlayerList} 自己持有一个 {@code playerIo}（构造时注入，目录固定在
 * 主世界存档的 {@code players/data/} 下），读写都走它。所以只改
 * {@code MinecraftServer} 是没用的 —— 必须在这里替换。
 *
 * <p>两条路径：
 * <ul>
 *   <li>{@code save(ServerPlayer)}：玩家在线，按他所在维度判断归属；</li>
 *   <li>{@code loadPlayerData(NameAndId)}：玩家还没进世界，按预先登记的目标存档判断。</li>
 * </ul>
 */
@Mixin(PlayerList.class)
public abstract class PlayerListStorageMixin {

    private static final String PLAYER_IO_FIELD =
            "Lnet/minecraft/server/players/PlayerList;playerIo:Lnet/minecraft/world/level/storage/PlayerDataStorage;";

    @ModifyExpressionValue(
            method = "save(Lnet/minecraft/server/level/ServerPlayer;)V",
            at = @At(value = "FIELD", target = PLAYER_IO_FIELD))
    private PlayerDataStorage hubsuite$routeSave(PlayerDataStorage original) {
        PlayerDataStorage routed = PlayerDataRouter.resolveCurrent();
        return routed != null ? routed : original;
    }

    @ModifyExpressionValue(
            method = "loadPlayerData",
            at = @At(value = "FIELD", target = PLAYER_IO_FIELD))
    private PlayerDataStorage hubsuite$routeLoad(PlayerDataStorage original) {
        PlayerDataStorage routed = PlayerDataRouter.resolvePending();
        return routed != null ? routed : original;
    }

    @Inject(method = "save(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"))
    private void hubsuite$enterSave(ServerPlayer player, CallbackInfo ci) {
        PlayerDataRouter.enter(player);
    }

    @Inject(method = "save(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("RETURN"))
    private void hubsuite$exitSave(ServerPlayer player, CallbackInfo ci) {
        PlayerDataRouter.exit();
    }

    @Inject(method = "loadPlayerData", at = @At("HEAD"))
    private void hubsuite$enterLoad(NameAndId nameAndId,
                                    CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        PlayerDataRouter.enterLoad(nameAndId.id());
    }

    @Inject(method = "loadPlayerData", at = @At("RETURN"))
    private void hubsuite$exitLoad(NameAndId nameAndId,
                                   CallbackInfoReturnable<Optional<CompoundTag>> cir) {
        PlayerDataRouter.exitLoad();
    }
}
