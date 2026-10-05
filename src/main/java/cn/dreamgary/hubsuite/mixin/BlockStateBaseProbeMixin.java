package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诊断：观察 {@code BlockStateBase.useItemOn} / {@code useWithoutItem} 的真实返回值。
 *
 * <p>Fabric 在这两个方法上各挂了一个事件，规则都是「处理器返回非 PASS 就
 * {@code cir.setReturnValue(...)} **覆盖方块自己的结果**」：
 * <pre>
 *   callUseItemOnEvent(...) {
 *       result = BlockEvents.USE_ITEM_ON.invoker().useItemOn(...);
 *       if (result != PASS) cir.setReturnValue(result);
 *   }
 * </pre>
 * 而 {@code ServerPlayerGameMode.useItemOn} 只有在
 * {@code state.useItemOn(...) instanceof TryEmptyHandInteraction} 时，
 * 才会继续调用 {@code useWithoutItem} —— 也就是**开箱子**的那条路。
 *
 * <p>所以任何一个模组只要在这个事件里返回了非 PASS，箱子就会**永远打不开**，
 * 而且症状极具迷惑性：右键毫无反应、没有报错、放方块却完全正常
 * （放方块走的是另一条分支）。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseProbeMixin {

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void hubsuite$probeUseItemOn(ItemStack stack, Level level, Player player,
                                         InteractionHand hand, BlockHitResult hit,
                                         CallbackInfoReturnable<InteractionResult> cir) {
        try {
            var self = (BlockBehaviour.BlockStateBase) (Object) this;
            HubSuite.logger().info(
                    "[交互诊断] BlockState.useItemOn 返回：{} 方块 {} @ {}｜hand={}｜→ {}",
                    player.getName().getString(),
                    self.getBlock().getName().getString(), hit.getBlockPos(),
                    hand, cir.getReturnValue());
        } catch (Throwable ignored) {
            // 诊断失败不影响游戏
        }
    }

    @Inject(method = "useWithoutItem", at = @At("HEAD"))
    private void hubsuite$probeUseWithoutItem(Level level, Player player, BlockHitResult hit,
                                              CallbackInfoReturnable<InteractionResult> cir) {
        try {
            var self = (BlockBehaviour.BlockStateBase) (Object) this;
            HubSuite.logger().info(
                    "[交互诊断] BlockState.useWithoutItem 被调用：{} 方块 {} @ {}",
                    player.getName().getString(),
                    self.getBlock().getName().getString(), hit.getBlockPos());
        } catch (Throwable ignored) {
            // 诊断失败不影响游戏
        }
    }
}
