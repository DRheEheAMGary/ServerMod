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
     * 平台所在区块还没就位，本次没铺；调用方应稍后重试。
     *
     * <p>这个返回值是必需的：{@code getBlockState} 在**未加载**区块上会走
     * {@code getChunk(...).join()} —— 把区块同步生成出来。建岛/铺平台是
     * "登记待补铺 + 每 tick 重试"的延迟设计，在这里同步生成就把它变成了
     * 每 tick 卡一次主线程（实测冷存档上直接触发看门狗强杀）：
     * <pre>
     *   SpawnPlatform.build → Level.getBlockState → ServerChunkCache.getChunk
     *     → BlockableEventLoop.managedBlock（阻塞 60 秒，服务端被强杀）
     * </pre>
     */
    public static final int CHUNKS_NOT_READY = -1;

    /**
     * 在指定出生点铺平台。
     *
     * @param radius 平台半径（不含护栏那一圈）
     * @return 实际放置的方块数；0 表示平台已存在、本次跳过；
     *         {@link #CHUNKS_NOT_READY} 表示区块没就位，**需要稍后重试**
     */
    public static int build(ServerLevel level, PlayableWorld.SpawnPoint spawn, int radius) {
        return build(level, spawn, radius, net.minecraft.world.level.block.Blocks.STONE);
    }

    /** 同上，但可以指定平台方块（海洋维度用沙子更自然）。 */
    public static int build(ServerLevel level, PlayableWorld.SpawnPoint spawn, int radius,
                            net.minecraft.world.level.block.Block block) {
        if (spawn == null) {
            return 0;
        }
        BlockPos center = BlockPos.containing(spawn.x(), spawn.y(), spawn.z());
        int r = Math.max(3, Math.min(radius, 64));

        /*
         * 先确认要碰的区块都**已经就位**。
         *
         * 平台半径 r（再加护栏）会跨区块：块坐标 [center-r-1, center+r+1]。
         * 只要有一个没加载，这次就什么都不做 —— 由调用方下一 tick 再来。
         * 绝不能让下面的 getBlockState / setBlockAndUpdate 去同步生成它们。
         */
        int minCx = (center.getX() - r - 1) >> 4;
        int maxCx = (center.getX() + r + 1) >> 4;
        int minCz = (center.getZ() - r - 1) >> 4;
        int maxCz = (center.getZ() + r + 1) >> 4;
        /*
         * 先确认要碰的区块**真的到 FULL 了**。
         *
         * 平台半径 r（再加护栏）会跨区块：块坐标 [center-r-1, center+r+1]。
         *
         * 注意不能用 {@code hasChunk}（或 getChunkSource().hasChunk）来判断 ——
         * 那个对"已加载到任意非空阶段"都返回 true，但 {@code getBlockState}
         * 要的是 **FULL** 区块，没到就 {@code getChunk(...).join()} 同步等下去。
         * 实测：用 hasChunk 当守卫仍然被看门狗强杀（崩溃栈就落在下面那行
         * getBlockState 上）。而 {@code getChunkNow} 不阻塞 —— 没到 FULL 就返回 null。
         */
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    return CHUNKS_NOT_READY;
                }
            }
        }

        // 中心已经是实体方块 → 认为平台已存在，不再重复放置
        if (!level.getBlockState(center.below()).isAir() && !level.getBlockState(center).isAir()) {
            HubSuite.logger().debug("{} 的出生点已有实心地面，跳过平台生成。",
                    level.dimension().identifier());
            return 0;
        }

        // 大厅用石英+海晶灯+屏障护栏；其它场景（例如海洋维度）用什么方块就铺什么，
        // 并且不加护栏 —— 海上的小沙洲不需要隐形墙。
        boolean decorative = block == Blocks.STONE;
        BlockState floor = (decorative ? Blocks.SMOOTH_QUARTZ : block).defaultBlockState();
        BlockState light = (decorative ? Blocks.SEA_LANTERN : block).defaultBlockState();
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

        // 2) 边缘屏障（隐形护栏）—— 只有大厅需要，海上的小沙洲不加墙
        for (int dx = -r - 1; decorative ? dx <= r + 1 : false; dx++) {
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

        // 每次铺平台都会调用（含启动时的补铺重试）—— 降到 debug，别刷日志
        HubSuite.logger().debug("出生点平台已生成：{} 中心 ({}, {}, {})，半径 {}，共 {} 个方块。",
                level.dimension().identifier(),
                center.getX(), center.getY(), center.getZ(), r, placed);
        return placed;
    }
}
