package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诊断：观察 {@code ServerPlayerGameMode.useItemOn} 的进入与返回。
 *
 * <p>为什么需要这条：Fabric 把 {@code UseBlockCallback} 注入在这个方法的**开头**，
 * 规则是「第一个返回非 PASS 的处理器胜出，整个方法立刻 return」。
 * 所以只要有任何一方（本模组或别的模组）返回了非 PASS，
 * {@code ChestBlock.useWithoutItem} 就永远不会被调用 ——
 * 表现正是"右键箱子毫无反应、界面不开、日志无报错"。
 *
 * <p>判读方式：
 * <ul>
 *   <li>只有"进入"没有"返回" → 中途抛异常；</li>
 *   <li>返回 {@code Fail} → 被某个 {@code UseBlockCallback} 取消了（谁干的看这一轮的其它日志）；</li>
 *   <li>返回 {@code Pass} → 回调都放行了，是分支没走到 {@code useWithoutItem}。</li>
 * </ul>
 */
@Mixin(net.minecraft.server.level.ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeProbeMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void hubsuite$head(ServerPlayer player, Level level, ItemStack stack,
                               InteractionHand hand, BlockHitResult hit,
                               CallbackInfoReturnable<InteractionResult> cir) {
        try {
            HubSuite.logger().info(
                    "[交互诊断] useItemOn 进入：{} 目标 {}｜hand={}｜主手={}｜副手={}｜潜行={}｜模式={}",
                    player.getName().getString(), hit.getBlockPos(), hand,
                    player.getMainHandItem().getItem(),
                    player.getOffhandItem().getItem(),
                    player.isSecondaryUseActive(),
                    player.gameMode());
        } catch (Throwable ignored) {
            // 诊断失败不影响游戏
        }
    }

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void hubsuite$return(ServerPlayer player, Level level, ItemStack stack,
                                 InteractionHand hand, BlockHitResult hit,
                                 CallbackInfoReturnable<InteractionResult> cir) {
        try {
            HubSuite.logger().info("[交互诊断] useItemOn 返回：{} 目标 {} → {}",
                    player.getName().getString(), hit.getBlockPos(), cir.getReturnValue());
        } catch (Throwable ignored) {
            // 诊断失败不影响游戏
        }
    }
}
