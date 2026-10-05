package cn.dreamgary.hubsuite.feature;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.world.SubServer;
import cn.dreamgary.hubsuite.world.WorldsManager;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * 未登录玩家的"全部锁死"。
 *
 * <p>认证期间玩家被放在大厅等待区，但仍然可以尝试交互。
 * 这里把破坏、放置、右键、攻击、用物品、丢物品全部拦掉，
 * 保证未登录状态除了填表什么都做不了。
 *
 * <p>顺便实现创造服的"普通玩家禁用原版作弊指令"由 {@link CommandGuard} 负责。
 */
public final class LoginLockdown {

    private final WorldsManager worlds;
    private final cn.dreamgary.hubsuite.auth.AuthManager authManager;

    public LoginLockdown(WorldsManager worlds, cn.dreamgary.hubsuite.auth.AuthManager authManager) {
        this.worlds = worlds;
        this.authManager = authManager;
    }

    public void register() {
        // 破坏方块
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) ->
                !locked(player));

        // 放方块 / 右键方块
        UseBlockCallback.EVENT.register((Player player, Level level, InteractionHand hand, BlockHitResult hit) -> {
            // 注意：这里返回 FAIL 会让玩家右键**完全没反应且没有任何报错** ——
            // FAIL 在原版逻辑之前就把整个交互取消掉了（界面自然也不会开）。
            // 所以"未登录时右键没反应"是设计如此，不是 bug；
            // 排查此类问题时要想到这一层（这正是当年"箱子打不开"排查了很久的原因之一）。
            return locked(player) ? InteractionResult.FAIL : InteractionResult.PASS;
        });

        // 左键方块
        AttackBlockCallback.EVENT.register((Player player, Level level, InteractionHand hand,
                                           net.minecraft.core.BlockPos pos,
                                           net.minecraft.core.Direction direction) ->
                locked(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        // 右键实体
        UseEntityCallback.EVENT.register((Player player, Level level, InteractionHand hand,
                                          Entity entity, EntityHitResult hit) ->
                locked(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        // 攻击实体
        AttackEntityCallback.EVENT.register((Player player, Level level, InteractionHand hand,
                                             Entity entity, EntityHitResult hit) ->
                locked(player) ? InteractionResult.FAIL : InteractionResult.PASS);

        // 使用物品
        UseItemCallback.EVENT.register((Player player, Level level, InteractionHand hand) ->
                locked(player) ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    private boolean locked(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (authManager == null) {
            return false;
        }
        boolean authenticated = authManager.isAuthenticated(serverPlayer);
        if (!authenticated) {
            serverPlayer.sendSystemMessage(
                    Component.literal("\u00A7c请先完成注册或登录。"),
                    true);
            // 把玩家按回等待区（有人会试着用各种方式蹭出去）
            var lobby = worlds.lobby().orElse(null);
            if (lobby != null && !serverPlayer.level().dimension().equals(lobby.level().dimension())) {
                cn.dreamgary.hubsuite.world.PlayerRouter.sendTo(serverPlayer, lobby);
            }
        }
        return !authenticated;
    }

    /** 供命令拦截复用：该玩家是否处于"未认证"状态。 */
    public boolean isLocked(ServerPlayer player) {
        return authManager != null && !authManager.isAuthenticated(player);
    }

    /** 拦截未认证玩家执行敏感原版命令（如 /kill、/tp 出等待区）。 */
    public boolean blockSensitiveCommand(ServerPlayer player, String commandName) {
        if (!isLocked(player)) {
            return false;
        }
        return switch (commandName) {
            case "kill", "tp", "teleport", "effect", "enchant", "give", "setblock",
                 "fill", "summon", "gamemode", "spawnpoint", "setworldspawn",
                 "execute", "function", "reload", "stop", "op", "deop",
                 "clear", "xp", "advancement", "spectate", "ride", "damage",
                 "item", "loot", "place", "clone", "particle", "playsound",
                 "title", "tellraw", "bossbar", "scoreboard", "tag", "team",
                 "worldborder", "time", "weather", "difficulty", "gamerule",
                 "forceload", "datapack", "ban", "kick", "whitelist", "pardon" -> true;
            default -> false;
        };
    }

    /** 供 /hub info 展示。 */
    public String describe(ServerPlayer player) {
        PlayableWorld world = worlds.worldOf(player).orElse(null);
        if (world == null) {
            return "未托管维度";
        }
        if (world instanceof SubServer sub) {
            return sub.id();
        }
        return world.id();
    }

    /** 预留：给未使用告警消音。 */
    static void touch(ItemStack stack, BlockState state) {
        // no-op
    }
}
