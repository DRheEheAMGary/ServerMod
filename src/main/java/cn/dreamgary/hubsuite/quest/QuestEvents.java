package cn.dreamgary.hubsuite.quest;

import cn.dreamgary.hubsuite.HubSuite;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.BlockEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 把游戏事件接到任务进度上。
 *
 * <p>刻意**只用可靠且低开销的事件**：
 * <ul>
 *   <li>破坏方块 —— {@code PlayerBlockBreakEvents.AFTER}；</li>
 *   <li>放置方块 —— {@code BlockEvents.USE_ITEM_ON} 里判断手上是不是方块（Fabric 没有独立的放置事件）；</li>
 *   <li>击杀 —— {@code ServerLivingEntityEvents.AFTER_DEATH}；</li>
 *   <li>到达高度 / 持有量 —— 每 20 tick 扫一次在线玩家（都是很轻的比较）。</li>
 * </ul>
 *
 * <p>所有回调都包了 try/catch：任务系统出问题绝不能影响正常游戏。
 */
public final class QuestEvents {

    private static int tickCounter;

    private QuestEvents() {
    }

    public static void register() {
        // 破坏方块
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, entity) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return;
            }
            try {
                var manager = HubSuite.quests();
                if (manager == null || !manager.config().enabled) {
                    return;
                }
                if (!inIslandWorld(serverPlayer)) {
                    return;   // 只在空岛相关维度里计进度
                }
                manager.advance(serverPlayer, Quest.GoalType.BREAK, blockId(state), 1);
            } catch (Throwable t) {
                HubSuite.logger().error("任务系统：破坏方块统计失败", t);
            }
        });

        // 放置方块（用"手持方块右键"近似）
        BlockEvents.USE_ITEM_ON.register((stack, state, level, pos, player, hand, hit) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            try {
                var manager = HubSuite.quests();
                if (manager == null || !manager.config().enabled
                        || !inIslandWorld(serverPlayer)) {
                    return net.minecraft.world.InteractionResult.PASS;
                }
                if (stack.getItem() instanceof BlockItem blockItem) {
                    String id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString();
                    manager.advance(serverPlayer, Quest.GoalType.PLACE, id, 1);
                }
            } catch (Throwable t) {
                HubSuite.logger().error("任务系统：放置方块统计失败", t);
            }
            return net.minecraft.world.InteractionResult.PASS;
        });

        // 击杀
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register(
                (entity, damageSource) -> {
                    try {
                        var manager = HubSuite.quests();
                        if (manager == null || !manager.config().enabled) {
                            return;
                        }
                        if (!(damageSource.getEntity() instanceof ServerPlayer killer)) {
                            return;
                        }
                        if (!inIslandWorld(killer)) {
                            return;
                        }
                        manager.advance(killer, Quest.GoalType.KILL, entityId(entity), 1);
                    } catch (Throwable t) {
                        HubSuite.logger().error("任务系统：击杀统计失败", t);
                    }
                });

        // 到达高度 + 持有量同步（每 20 tick = 1 秒）
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % 20 != 0) {
                return;
            }
            var manager = HubSuite.quests();
            if (manager == null || !manager.config().enabled) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    if (!inIslandWorld(player)) {
                        continue;
                    }
                    manager.syncHaveGoals(player);
                    // 到达高度：按"当前 Y 是否达标"直接设置，而不是累加
                    for (Quest quest : manager.config().quests) {
                        if (quest.goalType() != Quest.GoalType.REACH_Y) {
                            continue;
                        }
                        int target = parseInt(quest.goalArg(), Integer.MIN_VALUE);
                        if (target == Integer.MIN_VALUE) {
                            continue;
                        }
                        if (player.getY() >= target) {
                            manager.advance(player, Quest.GoalType.REACH_Y, quest.goalArg(),
                                    quest.amount);
                        }
                    }
                } catch (Throwable t) {
                    HubSuite.logger().error("任务系统：tick 检查失败", t);
                }
            }
        });
    }

    /** 只在空岛相关维度里计进度（避免在大厅/生存服刷任务）。 */
    private static boolean inIslandWorld(ServerPlayer player) {
        var islands = HubSuite.islands();
        return islands != null && islands.isSkyblock(player.level());
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String entityId(LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 供 GUI 显示"这个物品是什么"用。 */
    static ItemStack iconOf(String itemId) {
        var item = QuestManager.itemOf(itemId);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }
}
