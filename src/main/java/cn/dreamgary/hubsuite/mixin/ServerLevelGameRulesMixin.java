package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.world.RulesManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让每个维度拥有自己的 {@link GameRules}。
 *
 * <p>原版实现是 {@code return this.server.getGameRules();}，即全局共享。
 * 本项目在方法返回处拦截：如果该维度注册过独立规则集就替换返回值。
 *
 * <p>之所以只用这一处 Mixin 就够了：原版 100% 的游戏逻辑（命令、玩家进阶、
 * 重生、刷怪、伤害等）都是通过 {@code level.getGameRules()} 取规则的，
 * 我们已用字节码扫描确认没有其它类直接调用 {@code MinecraftServer.getGameRules()}
 * （唯一的例外是 DedicatedServer 读 announceAdvancements 属性，属于全局开关）。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelGameRulesMixin {

    @Inject(method = "getGameRules", at = @At("RETURN"), cancellable = true)
    private void hubsuite$perLevelGameRules(CallbackInfoReturnable<GameRules> cir) {
        GameRules mine = RulesManager.gameRulesFor((ServerLevel) (Object) this);
        if (mine != null) {
            cir.setReturnValue(mine);
        }
    }
}
