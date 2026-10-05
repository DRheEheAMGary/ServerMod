package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 诊断：把 {@code ChestBlock.useWithoutItem} 内部的判定摊开。
 *
 * <p>用户反馈"右键能放方块，但箱子完全打不开"，而探针显示：
 * 数据包正常到达、两个模组回调都放行、原版闸门全过，**但 openMenu 一次都没被调用**。
 *
 * <p>那就只剩"方块自己拒绝开箱"这一种可能。原版有两条**完全静默**的拒绝理由：
 * <ol>
 *   <li>{@code getBlockEntity(pos)} 不是 {@code ChestBlockEntity}
 *       —— 方块实体丢了，此时它仍然返回 SUCCESS（挥手但不开界面）；</li>
 *   <li>{@code isChestBlockedAt(...)} —— 上方有实心方块，**或者有猫坐在箱子上**。</li>
 * </ol>
 * 这条日志把三者一次性打出来。
 */
@Mixin(ChestBlock.class)
public abstract class ChestBlockProbeMixin {

    @Inject(method = "useWithoutItem", at = @At("HEAD"))
    private void hubsuite$probeUseWithoutItem(BlockState state, Level level, BlockPos pos,
                                              Player player, BlockHitResult hit,
                                              CallbackInfoReturnable<InteractionResult> cir) {
        try {
            var be = level.getBlockEntity(pos);
            var above = level.getBlockState(pos.above());
            boolean blocked = ChestBlock.isChestBlockedAt(level, pos);
            boolean catOnTop = !level.getEntitiesOfClass(
                    net.minecraft.world.entity.animal.feline.Cat.class,
                    new net.minecraft.world.phys.AABB(pos.above())).isEmpty();
            HubSuite.logger().info(
                    "[交互诊断] ChestBlock.useWithoutItem：{} 目标 {}｜方块实体 {}｜"
                            + "判定为ChestBlockEntity={}｜上方 {}｜受阻={}｜猫坐上面={}",
                    player.getName().getString(), pos,
                    be == null ? "null" : be.getClass().getSimpleName(),
                    be instanceof ChestBlockEntity,
                    above.getBlock().getName().getString(),
                    blocked, catOnTop);
        } catch (Throwable t) {
            HubSuite.logger().warn("[交互诊断] ChestBlock 探针失败：{}", t.toString());
        }
    }
}
