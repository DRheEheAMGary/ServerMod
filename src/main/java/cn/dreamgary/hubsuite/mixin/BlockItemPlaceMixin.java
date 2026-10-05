package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.quest.Quest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 任务系统："放置方块"的**精确**计数。
 *
 * <p>为什么不用 Fabric 的 {@code BlockEvents.USE_ITEM_ON}：那个事件在
 * {@code BlockStateBase.useItemOn} 的**开头**触发，只能看到"手上拿着方块 +
 * 右键了"，看不到**有没有真的放下去** —— 对着箱子、告示牌、工作台右键
 * （方块自己把交互吃掉、根本不会走到放置）也会被记一次。
 * 更糟的是那个事件要求返回 {@code null} 才算"不处理"，返回 {@code PASS}
 * 会覆盖方块自己的返回值，曾经造成**全服箱子打不开**（详见 QuestEvents 的注释）。
 *
 * <p>这里直接挂在 {@link BlockItem#place} 的**返回处**：
 * <ul>
 *   <li>只有真的走到"放置"这一步才会被调用（被方块吃掉的交互走不到这里）；</li>
 *   <li>只有 {@link InteractionResult#consumesAction()}（SUCCESS/CONSUME）才算数，
 *       {@code FAIL}（放不下、位置非法、没材料）不计数；</li>
 *   <li>顺带把 QuestEvents 里那个监听器整个删掉了 —— 这类"覆盖方块返回值"的
 *       风险从代码里消失。</li>
 * </ul>
 *
 * <p>客户端也会调用 {@code place}（本地预测），所以必须用
 * {@code instanceof ServerPlayer} 过滤，否则会重复计数。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlaceMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void hubsuite$countPlacement(BlockPlaceContext context,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        try {
            InteractionResult result = cir.getReturnValue();
            if (result == null || !result.consumesAction()) {
                return;   // 没放下去（FAIL / PASS）→ 不算
            }
            if (!(context.getPlayer() instanceof ServerPlayer player)) {
                return;   // 客户端本地预测
            }
            var manager = HubSuite.quests();
            if (manager == null || !manager.config().enabled) {
                return;
            }
            var islands = HubSuite.islands();
            if (islands == null || !islands.isSkyblock(player.level())) {
                return;   // 只在空岛相关维度里计进度（与其它目标类型一致）
            }
            String id = BuiltInRegistries.BLOCK.getKey(
                    ((BlockItem) (Object) this).getBlock()).toString();
            manager.advance(player, Quest.GoalType.PLACE, id, 1);
        } catch (Throwable t) {
            // 任务系统出问题绝不能影响正常游戏
            HubSuite.logger().error("任务系统：放置方块统计失败", t);
        }
    }
}
