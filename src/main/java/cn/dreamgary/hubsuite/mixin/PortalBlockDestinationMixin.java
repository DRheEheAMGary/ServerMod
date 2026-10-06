package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.world.PortalLinks;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让**每个子服**拥有自己的下界 / 末地。
 *
 * <p><b>原版为什么做不到：</b>{@code NetherPortalBlock#getPortalDestination} 返回的
 * {@link TeleportTransition} 里的目标维度是**写死**的
 * {@code Level.NETHER} / {@code Level.END}。而
 * {@code MinecraftServer.levels} 这张表按 key 唯一 —— 没法给三个子服
 * 各注册一个 {@code minecraft:the_nether}，后来的会把先前的顶掉。
 *
 * <p><b>本模组的做法：</b>每个子服造自己的
 * {@code hubsuite:server_<id>_nether} / {@code hubsuite:server_<id>_end}
 * 维度，并在 {@link PortalLinks} 里登记对应关系；这里只把原版算出来的
 * 目标维度**换掉**，位置/朝向/建门逻辑全部沿用原版。
 *
 * <p>这样从生存服进传送门到的是生存服的下界，从那个下界回来回到生存服 ——
 * 创造服的玩家不可能走过去串门。
 *
 * <p>没登记过的维度（大厅、原版主世界）原样返回，**不改变原版行为**。
 */
@Mixin({NetherPortalBlock.class, EndPortalBlock.class})
public abstract class PortalBlockDestinationMixin {

    @ModifyReturnValue(method = "getPortalDestination", at = @At("RETURN"))
    private TeleportTransition hubsuite$redirectToOwnDimension(TeleportTransition original,
                                                               ServerLevel fromLevel,
                                                               Entity entity,
                                                               BlockPos pos) {
        if (original == null || fromLevel == null) {
            return original;
        }
        try {
            ServerLevel vanillaTarget = original.newLevel();
            if (vanillaTarget == null) {
                return original;
            }
            ResourceKey<Level> targetKey = vanillaTarget.dimension();
            // 只在下界/末地这两个写死的目标上做手脚，其它一律不动
            if (!Level.NETHER.equals(targetKey) && !Level.END.equals(targetKey)) {
                return original;
            }
            ResourceKey<Level> redirected = PortalLinks.redirect(
                    fromLevel.dimension(), targetKey);
            if (redirected == null || redirected.equals(targetKey)) {
                return original;   // 没登记 / 不需要改
            }
            var server = fromLevel.getServer();
            ServerLevel destination = PortalLinks.levelOf(server, redirected);
            if (destination == null) {
                // 目标维度没造出来（配置关掉或构造失败）→ 保留原版行为，
                // 总比把玩家传进一个不存在的维度要好。
                return original;
            }
            /*
             * 重新构造 TeleportTransition。
             *
             * 位置沿用原版算出来的（下界 1:8 缩放已经由原版按"主世界↔下界"
             * 算过了，而我们的下界维度和子服主维度同样是 1:8 关系，
             * 所以坐标是合适的，不需要自己再缩放）。
             */
            return new TeleportTransition(
                    destination,
                    original.position(),
                    original.deltaMovement(),
                    original.yRot(),
                    original.xRot(),
                    original.postTeleportTransition());
        } catch (Throwable t) {
            // 绝不能因为重定向失败就把玩家卡在传送门里
            return original;
        }
    }
}
