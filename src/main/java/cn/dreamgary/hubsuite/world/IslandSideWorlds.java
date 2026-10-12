package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.island.IslandConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.Locale;
import java.util.Map;

/**
 * 给空岛 / 海岛维度造**它们自己的下界与末地**。
 *
 * <p>v0.4.0 用户要求：
 * <ul>
 *   <li>空岛服用"空岛式"下界（虚空 + 传送门落点补一座下界岩小岛）；</li>
 *   <li>海岛服用"岩浆海"下界（液面抬到 Y=96）；</li>
 *   <li>两座都要**原版末地**。</li>
 * </ul>
 *
 * <p>每个维度仍然是**独立存档**（{@link SubServer#createSideDimension} 负责），
 * 造完统一登记到 {@link PortalLinks}：以后从岛屿维度点火，
 * 传送门就会改道到它自己的那一界，而不是全服共用的下界。
 */
public final class IslandSideWorlds {

    private IslandSideWorlds() {
    }

    /**
     * 按岛型配置给每个岛屿维度造侧维度。
     *
     * @param islandDims 岛型 id → 它对应的维度入口（classic / ocean）
     */
    public static void create(SubServer sub, IslandConfig config,
                              Map<String, SubServer.Entry> islandDims) {
        if (sub == null || config == null || config.types == null || islandDims == null) {
            return;
        }
        for (IslandConfig.IslandType type : config.types) {
            if (type == null || type.id == null) {
                continue;
            }
            SubServer.Entry main = islandDims.get(type.id);
            if (main == null) {
                continue;   // 这个岛型没有对应维度（配置里删了/加载失败）
            }
            ResourceKey<Level> nether = type.ownNether ? createNether(sub, type) : null;
            ResourceKey<Level> end = type.ownEnd ? createEnd(sub, type) : null;
            if (nether != null || end != null) {
                PortalLinks.register(main.dimension(), nether, end);
                HubSuite.logger().info("岛屿维度 '{}' 的独立维度已就绪：下界={} 末地={}",
                        type.id,
                        nether == null ? "未启用" : nether.identifier().toString(),
                        end == null ? "未启用" : end.identifier().toString());
            } else {
                HubSuite.logger().warn("岛屿维度 '{}' 没有造出下界/末地（配置 ownNether={} ownEnd={}）",
                        type.id, type.ownNether, type.ownEnd);
            }
        }
    }

    private static ResourceKey<Level> createNether(SubServer sub, IslandConfig.IslandType type) {
        var server = sub.level().getServer();
        if (server == null) {
            return null;
        }
        String preset = type.netherPreset == null
                ? "" : type.netherPreset.trim().toLowerCase(Locale.ROOT);
        if ("vanilla".equals(preset)) {
            LevelStem stem = WorldFactory.vanillaStem(server, true);
            return stem == null ? null
                    : spawnAndRegister(sub, type, "nether", "下界", stem, 64.5, false);
        }
        ChunkGenerator generator;
        double spawnY;
        if ("lava_sea".equals(preset)) {
            generator = LavaSeaGenerator.create(server);
            spawnY = LavaSeaGenerator.LAVA_SEA_LEVEL + 1.5;
        } else {
            /*
             * 空岛式：纯虚空 + 下界荒原群系（会刷怪），并且**放行下界结构集**
             * （NETHER_COMPLEXES = 下界要塞 + 猪灵堡垒）—— 用户要求"地狱生成要塞和猪灵堡垒"。
             * 落点靠传送门那侧补的下界岩小岛。
             */
            generator = WorldFactory.voidGenerator(server, Biomes.NETHER_WASTES,
                    java.util.List.of(net.minecraft.world.level.levelgen.structure.BuiltinStructureSets.NETHER_COMPLEXES));
            spawnY = 64.5;
        }
        if (generator == null) {
            generator = WorldFactory.voidGenerator(server, Biomes.NETHER_WASTES);
        }
        LevelStem stem = WorldFactory.netherStem(server, generator);
        return spawnAndRegister(sub, type, "nether", "下界", stem, spawnY, true);
    }

    private static ResourceKey<Level> createEnd(SubServer sub, IslandConfig.IslandType type) {
        var server = sub.level().getServer();
        if (server == null) {
            return null;
        }
        LevelStem stem = WorldFactory.vanillaStem(server, false);
        if (stem == null) {
            return null;
        }
        // 末地固定落在 (100, 50, 0) 的黑曜石平台上（原版 ServerLevel 构造时就铺好了）
        PlayableWorld.SpawnPoint spawn = new PlayableWorld.SpawnPoint(100.5, 50.0, 0.5, 0.0F, 0.0F);
        return sub.createSideDimension(type.id + "_end", "末地", stem, sub.config().seed, spawn);
    }

    private static ResourceKey<Level> spawnAndRegister(SubServer sub, IslandConfig.IslandType type,
                                                       String suffix, String label,
                                                       LevelStem stem, double spawnY,
                                                       boolean islandNether) {
        PlayableWorld.SpawnPoint spawn = new PlayableWorld.SpawnPoint(0.5, spawnY, 0.5, 0.0F, 0.0F);
        ResourceKey<Level> key = sub.createSideDimension(type.id + "_" + suffix, label, stem,
                sub.config().seed, spawn);
        if (key != null && islandNether) {
            // 这种下界要在传送门落点下面补一座下界岩小岛（见 PortalBlockDestinationMixin）
            PortalLinks.registerIslandNether(key);
        }
        return key;
    }
}
