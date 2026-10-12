package cn.dreamgary.hubsuite.world;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.island.IslandConfig;
import cn.dreamgary.hubsuite.island.IslandService;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 空岛服 / 海岛服里**自然生成的岛屿**（v0.4.0）。
 *
 * <p>用户要求："生成一些自然生成的岛屿，会有一些刷怪笼和宝箱"，
 * 并且"我可以去稍微搭建几个结构" —— 所以位置、半径、宝箱物资、
 * 刷怪笼生物、村庄房屋模板**全在 {@code islands.json} 里配**（见
 * {@link IslandConfig#naturalIslands}）。
 *
 * <p><b>为什么不能启动时一次铺完：</b>铺地形要读/写方块，而那些区块在启动时
 * 还没到 FULL，{@code setBlock} 会同步把区块拉起来 —— 主线程直接卡到看门狗强杀
 * （本项目踩过好几次）。所以这里跟"出生平台补铺"用同一套办法：
 * 先登记 + 打加载票据，每个 tick 检查一次，区块就绪了再铺。
 *
 * <p>铺过的位置记在 {@code config/hubsuite/skyblock/natural-islands.json}，
 * 重启后不会重铺（否则宝箱每次重启都被重新填满）。
 */
public final class NaturalIslandPlacer {

    private NaturalIslandPlacer() {
    }

    /** 一座待铺的自然岛。 */
    private record Spot(ServerLevel level, String typeId, int x, int z) {
    }

    private static final List<Spot> PENDING = new CopyOnWriteArrayList<>();
    private static final Set<String> TICKETED = ConcurrentHashMap.newKeySet();
    private static final Set<String> PLACED = ConcurrentHashMap.newKeySet();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TypeToken<Set<String>> SET_TYPE = new TypeToken<>() {
    };
    private static Path storeFile;

    // ------------------------------------------------------------------
    // 登记
    // ------------------------------------------------------------------

    /**
     * 由 {@code HubSuite} 在空岛服务就绪后调用：把配置里的位置登记成待铺任务。
     */
    public static void registerAll(IslandService islands, IslandConfig config) {
        if (islands == null || config == null || config.naturalIslands == null) {
            return;
        }
        storeFile = FabricLoader.getInstance().getConfigDir()
                .resolve(HubSuite.MOD_ID).resolve("skyblock").resolve("natural-islands.json");
        loadPlaced();
        int queued = 0;
        for (var entry : config.naturalIslands.entrySet()) {
            var type = islands.type(entry.getKey()).orElse(null);
            if (type == null) {
                HubSuite.logger().warn("自然岛配置里的岛型 '{}' 不存在，已跳过。", entry.getKey());
                continue;
            }
            ServerLevel level = type.entry().level();
            List<int[]> spots = entry.getValue();
            if (spots == null) {
                continue;
            }
            for (int[] pos : spots) {
                if (pos == null || pos.length < 2) {
                    continue;
                }
                String key = key(level, pos[0], pos[1]);
                if (PLACED.contains(key) || isPending(key)) {
                    continue;
                }
                PENDING.add(new Spot(level, entry.getKey(), pos[0], pos[1]));
                queued++;
            }
        }
        HubSuite.logger().info("自然岛：本次登记 {} 座待铺（已铺过 {} 座）。", queued, PLACED.size());
    }

    private static boolean isPending(String key) {
        for (Spot s : PENDING) {
            if (key(s).equals(key)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 每 tick 补铺
    // ------------------------------------------------------------------

    public static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        for (Spot spot : List.copyOf(PENDING)) {
            try {
                if (!chunksReady(spot.level(), spot.x(), spot.z())) {
                    // 打一张加载票据把这一片催起来，下一 tick 再看
                    if (TICKETED.add(key(spot))) {
                        spot.level().getChunkSource().addTicketWithRadius(
                                TicketType.PLAYER_LOADING,
                                new ChunkPos(spot.x() >> 4, spot.z() >> 4), 1);
                    }
                    continue;
                }
                build(spot);
                PENDING.remove(spot);
                PLACED.add(key(spot));
                savePlaced();
                HubSuite.logger().info("自然岛已铺：{} 的 ({}, {})",
                        spot.level().dimension().identifier(), spot.x(), spot.z());
            } catch (Throwable t) {
                PENDING.remove(spot);
                HubSuite.logger().warn("自然岛 ({}, {}) 铺设失败：{}",
                        spot.x(), spot.z(), t.toString());
            }
        }
    }

    private static boolean chunksReady(ServerLevel level, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 铺岛
    // ------------------------------------------------------------------

    private static void build(Spot spot) {
        var service = HubSuite.islands();
        IslandConfig config = service == null ? null : service.config();
        if (config == null) {
            return;
        }
        ServerLevel level = spot.level();
        int radius = config.naturalIslandRadius;
        int baseY = baseY(level, config);
        RandomSource random = RandomSource.create(seedFor(spot));
        RandomSource levelRandom = level.getRandom();

        /*
         * 1) 地形：圆盘，最外一圈是沙（像自然小岛的海岸）。
         *
         * 配 naturalIslandTerrain=false 时**不铺岛** —— 岛完全由结构模板提供
         * （用户要求"自然岛改成结构"）。但这时仍铺一块半径 3 的小台，
         * 否则宝箱/刷怪笼会浮在虚空里。
         */
        int terrainRadius = config.naturalIslandTerrain ? radius : Math.min(radius, 3);
        for (int dx = -terrainRadius; dx <= terrainRadius; dx++) {
            for (int dz = -terrainRadius; dz <= terrainRadius; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > terrainRadius) {
                    continue;
                }
                boolean beach = config.naturalIslandTerrain && d > terrainRadius - 1.5;
                BlockState top = (beach ? Blocks.SAND : Blocks.GRASS_BLOCK).defaultBlockState();
                BlockState fill = (beach ? Blocks.SAND : Blocks.DIRT).defaultBlockState();
                int x = spot.x() + dx;
                int z = spot.z() + dz;
                level.setBlock(new BlockPos(x, baseY, z), top, 3);
                level.setBlock(new BlockPos(x, baseY - 1, z), fill, 3);
                level.setBlock(new BlockPos(x, baseY - 2, z), fill, 3);
                level.setBlock(new BlockPos(x, baseY - 3, z), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(new BlockPos(x, baseY - 4, z), Blocks.STONE.defaultBlockState(), 3);
                // 岛上方的方块清掉（海洋维度里可能正好压着水/海底）
                for (int y = baseY + 1; y <= baseY + 5; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
                        level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        // 2) 宝箱
        BlockPos chestPos = new BlockPos(spot.x() + 1, baseY + 1, spot.z());
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), 3);
        var chestEntity = level.getBlockEntity(chestPos);
        if (chestEntity instanceof net.minecraft.world.Container chest) {
            List<ItemStack> loot = parseLoot(config.naturalIslandLoot);
            for (int i = 0; i < loot.size() && i < chest.getContainerSize(); i++) {
                chest.setItem(i, loot.get(i));
            }
        }

        // 3) 刷怪笼
        if (!config.naturalIslandSpawners.isEmpty()) {
            String mobId = config.naturalIslandSpawners.get(
                    random.nextInt(config.naturalIslandSpawners.size()));
            EntityType<?> mob = BuiltInRegistries.ENTITY_TYPE
                    .getOptional(Identifier.parse(mobId)).orElse(null);
            if (mob != null) {
                BlockPos spawnerPos = new BlockPos(spot.x() - 1, baseY + 1, spot.z());
                level.setBlock(spawnerPos, Blocks.SPAWNER.defaultBlockState(), 3);
                if (level.getBlockEntity(spawnerPos) instanceof SpawnerBlockEntity spawner) {
                    spawner.setEntityId(mob, levelRandom);
                }
            }
        }

        // 4) 结构模板（村庄房屋 / 你自建的结构）—— 传说中"自然岛改成结构"
        placeStructure(level, spot, config, baseY, random);
    }

    /** 岛面高度：虚空世界用配置的 islandY，正常世界用海平面上一格。 */
    private static int baseY(ServerLevel level, IslandConfig config) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (generator instanceof FlatLevelSource) {
            return Math.max(level.getMinY() + 8, config.islandY);
        }
        return Math.max(level.getMinY() + 8, level.getSeaLevel() + 1);
    }

    /**
     * 在自然岛上摆一个**结构模板**（默认是原版村庄小房屋）。
     *
     * <p>结构底下会先垫一块底座（{@code naturalIslandStructureBase}）——
     * 用户要求"村庄需要底座"：房屋比岛大时不会悬空。
     * 想换成自己搭的结构：游戏里 {@code /structure save 名字:路径} 存下来，
     * 再把 id 填进 {@code naturalIslandStructures}。
     */
    private static void placeStructure(ServerLevel level, Spot spot, IslandConfig config,
                                       int baseY, RandomSource random) {
        if (config.naturalIslandStructures == null || config.naturalIslandStructures.isEmpty()) {
            return;
        }
        var server = level.getServer();
        if (server == null) {
            return;
        }
        String id = config.naturalIslandStructures.get(
                random.nextInt(config.naturalIslandStructures.size()));
        var template = server.getStructureManager().get(Identifier.parse(id)).orElse(null);
        if (template == null) {
            HubSuite.logger().warn("自然岛的结构模板 '{}' 不存在，跳过", id);
            return;
        }
        var size = template.getSize();
        int originX = spot.x() - size.getX() / 2;
        int originZ = spot.z() - size.getZ() / 2;
        if (config.naturalIslandStructureBase) {
            placeBase(level, originX, originZ, size.getX(), size.getZ(), baseY,
                    config.naturalIslandBaseMargin);
        }
        BlockPos origin = new BlockPos(originX, baseY + 1, originZ);
        template.placeInWorld(level, origin, origin, new StructurePlaceSettings(), random, 3);
    }

    /** 在结构轮廓下面垫一层底座：顶面草/土，下面石头。没加载的列跳过。 */
    private static void placeBase(ServerLevel level, int originX, int originZ,
                                  int sizeX, int sizeZ, int topY, int margin) {
        for (int x = originX - margin; x < originX + sizeX + margin; x++) {
            for (int z = originZ - margin; z < originZ + sizeZ + margin; z++) {
                if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
                    continue;
                }
                level.setBlock(new BlockPos(x, topY, z), Blocks.DIRT.defaultBlockState(), 3);
                level.setBlock(new BlockPos(x, topY - 1, z), Blocks.DIRT.defaultBlockState(), 3);
                level.setBlock(new BlockPos(x, topY - 2, z), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(new BlockPos(x, topY - 3, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 解析 {@code 物品id*数量} 列表。 */
    private static List<ItemStack> parseLoot(List<String> raw) {
        List<ItemStack> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        var registry = BuiltInRegistries.ITEM;
        for (String text : raw) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String id = text.trim();
            int amount = 1;
            int star = id.lastIndexOf('*');
            if (star > 0) {
                try {
                    amount = Math.max(1, Integer.parseInt(id.substring(star + 1).trim()));
                } catch (NumberFormatException ignored) {
                    amount = 1;
                }
                id = id.substring(0, star).trim();
            }
            Item item = registry.getOptional(Identifier.parse(id)).orElse(null);
            if (item == null || item == net.minecraft.world.item.Items.AIR) {
                HubSuite.logger().warn("自然岛宝箱里的物资 '{}' 不存在，已忽略。", text);
                continue;
            }
            result.add(new ItemStack(item, Math.min(amount, item.getDefaultMaxStackSize() * 4)));
        }
        return result;
    }

    private static String key(Spot spot) {
        return key(spot.level(), spot.x(), spot.z());
    }

    private static String key(ServerLevel level, int x, int z) {
        return level.dimension().identifier() + "@" + x + "," + z;
    }

    /** 位置相关的稳定随机种子：同一座岛每次铺出来都一样。 */
    private static long seedFor(Spot spot) {
        long h = spot.level().dimension().identifier().toString().hashCode();
        h = h * 31 + spot.x();
        h = h * 31 + spot.z();
        return h ^ 0x9E3779B97F4A7C15L;
    }

    // ------------------------------------------------------------------
    // 落盘（铺过哪些岛）
    // ------------------------------------------------------------------

    private static void loadPlaced() {
        try {
            if (storeFile == null || !Files.exists(storeFile)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
                Set<String> loaded = GSON.fromJson(reader, SET_TYPE.getType());
                if (loaded != null) {
                    PLACED.addAll(loaded);
                }
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("读取自然岛记录失败（会重铺一次）：{}", t.toString());
        }
    }

    private static void savePlaced() {
        try {
            if (storeFile == null) {
                return;
            }
            Files.createDirectories(storeFile.getParent());
            Path tmp = storeFile.resolveSibling("natural-islands.json.tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(new LinkedHashSet<>(PLACED), writer);
            }
            try {
                Files.move(tmp, storeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.io.IOException unsupported) {
                Files.move(tmp, storeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            HubSuite.logger().warn("保存自然岛记录失败：{}", t.toString());
        }
    }

    /** 自检用：还有多少座待铺。 */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** 自检用：已铺过多少座。 */
    public static int placedCount() {
        return PLACED.size();
    }
}
