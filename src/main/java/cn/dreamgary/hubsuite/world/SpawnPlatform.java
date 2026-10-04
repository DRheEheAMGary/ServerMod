package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 出生点安全平台生成器。
 *
 * <p>只要某个世界的出生点下方是空气（纯虚空的大厅/空岛服，或者把出生点设在
 * 半空中的普通世界），玩家一进去就会直接掉下去 —— 实测出现过"进生存服就摔死"。
 * 这个类负责在出生点铺一块有灯光的圆形平台，并在边缘加一圈隐形屏障防止误掉。
 *
 * <p>设计上刻意做得"朴素但可用"：
 * <ul>
 *   <li>地面：石英块（明亮、不抢眼）</li>
 *   <li>边缘：一圈海晶灯，既当护栏又当照明，避免虚空里漆黑一片</li>
 *   <li>护栏：屏障方块（隐形，玩家看不出来但走不出去）</li>
 *   <li>平台上方清空 4 格，免得重生在方块里</li>
 * </ul>
 *
 * <p>只在平台中心当前是空气时才生成，因此重启不会反复刷方块、也不会覆盖玩家改造。
 */
public final class SpawnPlatform {

    private SpawnPlatform() {
    }

    /**
     * 在指定出生点铺平台。
     *
     * @param radius 平台半径（不含护栏那一圈）
     * @return 实际放置的方块数；0 表示平台已存在、本次跳过
     */
    public static int build(ServerLevel level, PlayableWorld.SpawnPoint spawn, int radius) {
        if (spawn == null) {
            return 0;
        }
        BlockPos center = BlockPos.containing(spawn.x(), spawn.y(), spawn.z());
        int r = Math.max(3, Math.min(radius, 64));

        // 中心已经是实体方块 → 认为平台已存在，不再重复放置
        if (!level.getBlockState(center.below()).isAir() && !level.getBlockState(center).isAir()) {
            HubSuite.logger().debug("{} 的出生点已有实心地面，跳过平台生成。",
                    level.dimension().identifier());
            return 0;
        }

        BlockState floor = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        BlockState light = Blocks.SEA_LANTERN.defaultBlockState();
        BlockState fence = Blocks.BARRIER.defaultBlockState();

        int placed = 0;
        int floorY = center.getY() - 1;

        // 1) 圆形地面 + 边缘灯
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r + 0.5) {
                    continue;
                }
                boolean edge = dist > r - 0.5;
                BlockPos pos = new BlockPos(center.getX() + dx, floorY, center.getZ() + dz);
                level.setBlockAndUpdate(pos, edge && (Math.abs(dx) % 3 == 0) ? light : floor);
                placed++;
            }
        }

        // 2) 边缘屏障（隐形护栏）
        for (int dx = -r - 1; dx <= r + 1; dx++) {
            for (int dz = -r - 1; dz <= r + 1; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist <= r + 0.5 || dist > r + 1.5) {
                    continue;
                }
                level.setBlockAndUpdate(
                        new BlockPos(center.getX() + dx, floorY + 1, center.getZ() + dz), fence);
                level.setBlockAndUpdate(
                        new BlockPos(center.getX() + dx, floorY + 2, center.getZ() + dz), fence);
                placed += 2;
            }
        }

        // 3) 清空出生点上方的空间，避免卡在方块里
        for (int dy = 0; dy <= 4; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlockAndUpdate(
                            new BlockPos(center.getX() + dx, center.getY() + dy, center.getZ() + dz),
                            Blocks.AIR.defaultBlockState());
                }
            }
        }

        HubSuite.logger().info("出生点平台已生成：{} 中心 ({}, {}, {})，半径 {}，共 {} 个方块。",
                level.dimension().identifier(),
                center.getX(), center.getY(), center.getZ(), r, placed);
        return placed;
    }
}
