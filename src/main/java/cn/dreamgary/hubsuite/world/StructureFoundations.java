package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 给"天生悬空的结构"补一块**底座**。
 *
 * <p>用户要求："村庄和猪灵堡垒需要底部的底座，要塞不需要"。
 * 为什么需要：空岛式的下界是**纯虚空**，原版生成出来的猪灵堡垒会整片悬在空中；
 * 我们的村庄房屋摆在自然岛上，房子可能比岛还大，也会探出去。
 *
 * <p>做法：监听区块加载，只在本模组托管的维度（{@code hubsuite:*}）里找
 * {@code StructureStart}，结构 id 命中配置名单（默认只有
 * {@code minecraft:bastion_remnant}）时才在它的包围盒下面垫方块。
 * 纯事件驱动、不做扫描 —— 不用每 tick 去翻区块。
 *
 * <p>同一个结构只补一次（按包围盒记在内存里）—— 区块卸载再加载不会反复刷方块。
 */
public final class StructureFoundations {

    /** 已经补过底座的结构（包围盒指纹）。 */
    private static final Set<String> DONE = ConcurrentHashMap.newKeySet();

    private StructureFoundations() {
    }

    /** 由 {@code HubSuite} 在模组初始化时调用一次。 */
    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newlyLoaded) -> {
            try {
                onChunkLoad(level, chunk);
            } catch (Throwable t) {
                // 补底座失败绝不能影响区块加载
                HubSuite.logger().debug("补结构底座失败：{}", t.toString());
            }
        });
        HubSuite.logger().debug("结构底座服务已注册（名单见 config.json 的 structureFoundations）");
    }

    private static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        HubSuiteConfig config = HubSuite.configManager() == null
                ? null : HubSuite.configManager().config();
        if (config == null || config.structureFoundations == null
                || config.structureFoundations.isEmpty()) {
            return;
        }
        // 只管本模组托管的维度：原版世界的结构一律不动
        if (!"hubsuite".equals(level.dimension().identifier().getNamespace())) {
            return;
        }
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Block base = blockOf(config.structureFoundationBlock);
        for (var entry : chunk.getAllStarts().entrySet()) {
            StructureStart start = entry.getValue();
            if (start == null || !start.isValid()) {
                continue;
            }
            Identifier key = registry.getKey(entry.getKey());
            if (key == null || !config.structureFoundations.contains(key.toString())) {
                continue;
            }
            buildBase(level, start, base, config);
        }
    }

    private static void buildBase(ServerLevel level, StructureStart start, Block base,
                                  HubSuiteConfig config) {
        var box = start.getBoundingBox();
        String fingerprint = level.dimension().identifier() + "@"
                + box.minX() + "," + box.minY() + "," + box.minZ() + "-" + box.maxX() + "," + box.maxZ();
        if (!DONE.add(fingerprint)) {
            return;
        }
        BlockState state = base.defaultBlockState();
        int margin = config.structureFoundationMargin;
        int depth = config.structureFoundationDepth;
        int placed = 0;
        for (int x = box.minX() - margin; x <= box.maxX() + margin; x++) {
            for (int z = box.minZ() - margin; z <= box.maxZ() + margin; z++) {
                // 没加载的区块直接跳过：setBlock 会同步把区块拉起来（看门狗风险）
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                    continue;
                }
                for (int y = box.minY() - 1; y >= box.minY() - depth; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, state, 3);
                        placed++;
                    }
                }
            }
        }
        HubSuite.logger().info("已给结构补底座：{} @ ({}, {}, {})，{} 个方块",
                level.dimension().identifier(), box.minX(), box.minY(), box.minZ(), placed);
    }

    private static Block blockOf(String id) {
        try {
            Identifier key = Identifier.tryParse(id == null ? "" : id);
            if (key != null) {
                Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
                if (block != null && block != Blocks.AIR) {
                    return block;
                }
            }
        } catch (Throwable ignored) {
            // 配置写错就回落
        }
        return Blocks.NETHERRACK;
    }
}
