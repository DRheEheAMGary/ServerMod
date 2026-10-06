package cn.dreamgary.hubsuite.mixin;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.PortalLinks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BlockUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * 让**每个子服**拥有自己的下界 / 末地。
 *
 * <p><b>原版为什么做不到：</b>{@code NetherPortalBlock#getPortalDestination} 里
 * 目标维度是**写死**的 {@code Level.NETHER} / {@code Level.END}。而
 * {@code MinecraftServer.levels} 按 key 唯一 —— 没法给三个子服各注册一个
 * {@code minecraft:the_nether}，后来的会把先前的顶掉。
 *
 * <p><b>为什么不能只改返回值（早期实现就是这么错的）：</b>
 * 反编译确认原版流程是
 * <pre>
 *   target = from==NETHER ? OVERWORLD : NETHER
 *   dest   = server.getLevel(target)
 *   t      = getExitPortal(dest, ...)   ← **在这里找门/建门**，用的是 dest
 *   return t
 * </pre>
 * 只把 {@code t} 的维度换掉，建门早就发生在原版目标世界了。实测现象：
 * <b>玩家能进自定义下界，但那一侧没有出口门，落点还在半空/石头里</b>。
 *
 * <p><b>本做法：</b>整个 {@code getPortalDestination} 的结果由我们自己重算 ——
 * 目标维度换成子服自己的那一界，然后在**那个世界**里
 * 找门（{@code PortalForcer.findClosestPortalPosition}）或建门
 * （{@code PortalForcer.createPortal}），落点用
 * {@code PortalShape.findCollisionFreePosition} 保证不卡方块。
 * 算法逐行对齐原版 {@code createDimensionTransition}。
 */
@Mixin({NetherPortalBlock.class, EndPortalBlock.class})
public abstract class PortalBlockDestinationMixin {

    /** 原版 {@code PortalForcer} 里"找不到门"的哨兵值。 */
    private static final int NOTHING_FOUND = Integer.MIN_VALUE;

    @ModifyReturnValue(method = "getPortalDestination", at = @At("RETURN"))
    private TeleportTransition hubsuite$redirectToOwnDimension(TeleportTransition original,
                                                               ServerLevel fromLevel,
                                                               Entity entity,
                                                               BlockPos fromPos) {
        if (original == null || fromLevel == null || entity == null) {
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
            boolean isNether = Level.NETHER.equals(targetKey);
            ResourceKey<Level> redirected = PortalLinks.redirect(fromLevel.dimension(), targetKey);
            if (redirected == null || redirected.equals(targetKey)) {
                return original;   // 没登记 / 不需要改
            }
            ServerLevel destination = PortalLinks.levelOf(fromLevel.getServer(), redirected);
            if (destination == null) {
                // 目标维度没造出来（配置关掉或构造失败）→ 保留原版行为，
                // 总比把玩家送进一个不存在的维度好。
                return original;
            }

            /*
             * 坐标换算：原版已按"当前维度 ↔ 原版目标维度"算过（下界 1:8），
             * 而我们的下界维度与子服主维度同样是 1:8 关系，直接用原版算出的位置。
             *
             * 回到原版的算法：position() 就是 getPortalDestination 里
             * clampToBounds(x*scale, y, z*scale) 的结果。
             */
            Vec3 originalPos = original.position();
            BlockPos scaledPos = BlockPos.containing(originalPos.x, originalPos.y, originalPos.z);

            Vec3 landPos = this.hubsuite$findOrCreateExit(destination, entity, fromPos,
                    scaledPos, isNether);
            if (landPos == null) {
                return original;   // 建门失败（例如超出世界边界）→ 不改变原版行为
            }
            /*
             * 朝向沿用原版返回值的 yaw/xRot（原版在 createDimensionTransition 里
             * 按"进门方向 vs 门轴"算出来的），保证出电梯的朝向与预期一致。
             */
            return new TeleportTransition(
                    destination,
                    landPos,
                    Vec3.ZERO,
                    original.yRot(),
                    original.xRot(),
                    Relative.union(Relative.DELTA, Relative.ROTATION),
                    original.postTeleportTransition());
        } catch (Throwable t) {
            // 绝不能因为重定向失败就把玩家卡在传送门里
            HubSuite.logger().warn("重定向传送门出口失败，沿用原版行为：{}", t.toString());
            return original;
        }
    }

    /**
     * 在 {@code level} 里找一扇现成的门，没有就建一扇，返回落点。
     *
     * <p>逐行对齐原版 {@code NetherPortalBlock#getExitPortal} +
     * {@code createDimensionTransition}。
     *
     * @return 落点；{@code null} 表示建门失败（调用方保留原版行为）
     */
    private Vec3 hubsuite$findOrCreateExit(ServerLevel level, Entity entity, BlockPos fromPos,
                                           BlockPos scaledPos, boolean isNether) {
        var forcer = level.getPortalForcer();
        var border = level.getWorldBorder();

        BlockUtil.FoundRectangle rect;
        Optional<BlockPos> existing = forcer.findClosestPortalPosition(scaledPos, isNether, border);
        if (existing.isPresent()) {
            BlockPos pos = existing.get();
            BlockState state = level.getBlockState(pos);
            Direction.Axis axis = state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
            rect = BlockUtil.getLargestRectangleAround(pos, axis, 21, Direction.Axis.Y, 21,
                    p -> level.getBlockState(p) == state);
        } else {
            // 进门那一侧的门轴（拿不到就用 X），原版同款
            Direction.Axis axis = entity.level()
                    .getBlockState(fromPos)
                    .getOptionalValue(NetherPortalBlock.AXIS)
                    .orElse(Direction.Axis.X);
            Optional<BlockUtil.FoundRectangle> created = forcer.createPortal(scaledPos, axis);
            if (created.isEmpty()) {
                HubSuite.logger().warn("在维度 {} 的 {} 处建不出传送门（可能超出世界边界）",
                        level.dimension().identifier(), scaledPos);
                return null;
            }
            rect = created.get();
        }

        BlockPos min = rect.minCorner;
        BlockState minState = level.getBlockState(min);
        Direction.Axis portalAxis = minState
                .getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS)
                .orElse(Direction.Axis.X);
        EntityDimensions dim = entity.getDimensions(entity.getPose());

        // 原版：把玩家摆在门框内，再让 PortalShape 推到一个不卡碰撞的位置
        double along = (dim.width() / 2.0) + (rect.axis1Size - dim.width()) * 0.5;
        double up = rect.axis2Size - dim.height();
        double offset = 0.5;
        boolean alongIsX = portalAxis == Direction.Axis.X;

        Vec3 guess = new Vec3(
                min.getX() + (alongIsX ? along : offset),
                min.getY() + up,
                min.getZ() + (alongIsX ? offset : along));

        return PortalShape.findCollisionFreePosition(guess, level, entity, dim);
    }
}
