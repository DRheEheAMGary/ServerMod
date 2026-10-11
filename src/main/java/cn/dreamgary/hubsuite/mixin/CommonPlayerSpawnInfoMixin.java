package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.world.PortalLinks;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让客户端把我们的自定义下界 / 末地**当成原版那一界**（光影适配）。
 *
 * <p><b>为什么：</b>光影（Iris / OptiFine）判断维度是拿维度 **key** 跟
 * {@code Level.NETHER} / {@code Level.END} 比的，不看 {@code DimensionType.effects}
 * （而我们的维度类型本来就是原版 {@code minecraft:the_nether}，
 * 所以光影仍会把下界当主世界 —— 用户实测）。
 *
 * <p>我们的自定义下界 key 必须是为了子服隔离（各子服一份），不可能都用
 * {@code minecraft:the_nether}。所以这里只把**发给客户端**的 key 换掉：
 * 登录包和切维度包都走 {@code CommonPlayerSpawnInfo.write}，改这一处即可。
 *
 * <p><b>代价：</b>客户端会把"我们的下界"和"原版 minecraft:the_nether"当成同一个
 * key。玩家平时不会去原版那一界，基本无感。服务端内部完全不受影响。
 */
@Mixin(CommonPlayerSpawnInfo.class)
public class CommonPlayerSpawnInfoMixin {

    @ModifyExpressionValue(method = "write",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/network/protocol/game/CommonPlayerSpawnInfo;dimension:Lnet/minecraft/resources/ResourceKey;"))
    private ResourceKey<Level> hubsuite$clientFacingDimension(ResourceKey<Level> original) {
        return PortalLinks.clientFacing(original);
    }
}
