package cn.dreamgary.hubsuite.island;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 空岛配置：格子尺寸、岛型定义、初始物资。
 *
 * <p>序列化到 {@code config/hubsuite/islands.json}，支持热重载。
 * 新增一种岛型只要在这里加一条 {@link IslandType}，不需要改 Java 代码。
 */
public final class IslandConfig {

    /**
     * 海岛岛型**改动前**的默认物资表。
     *
     * <p>只用来识别"老存档里没被人动过的海岛物资"，好把后来新增的树苗补进去
     * （见 {@link #normalize()}）。管理员自己改过物资的话不会命中这个集合，也就不动它。
     */
    private static final java.util.List<String> OLD_OCEAN_LOOT = java.util.List.of(
            "minecraft:fishing_rod*1",
            "minecraft:oak_boat*1",
            "minecraft:kelp*8",
            "minecraft:bone_meal*8",
            "minecraft:bread*4",
            "minecraft:torch*8",
            "minecraft:sugar_cane*4");

    /**
     * 空岛服大厅里"选择岛屿"假人的外观。
     *
     * <p>它点的开箱子界面见 {@link IslandMenu}。坐标是**相对大厅出生点**的偏移，
     * 所以换了大厅平台尺寸也不用改。
     */
    public static final class HubNpcConfig {
        public boolean enabled = true;
        public double x = 0.5;
        public double y = 100.0;
        public double z = 5.5;
        public float yaw = 180.0F;
        public String name = "\u00A7a\u00A7l选择岛屿";
        public String skin = "auto";

        /** 转成通用的 NPC 外观配置。 */
        public cn.dreamgary.hubsuite.config.HubSuiteConfig.NpcConfig toNpcConfig() {
            var out = new cn.dreamgary.hubsuite.config.HubSuiteConfig.NpcConfig();
            out.enabled = enabled;
            out.x = x;
            out.y = y;
            out.z = z;
            out.yaw = yaw;
            out.name = name;
            out.skin = skin;
            return out;
        }
    }

    /** 空岛服大厅的选岛假人。 */
    public HubNpcConfig hubNpc = new HubNpcConfig();

    /** 空岛系统挂在哪个子服上（对应 servers 里的 id）。 */
    public String serverId = "skyblock";

    /** 每座岛的方格边长（方块）。 */
    public int plotSize = 256;

    /** 相邻方格之间的间隔（方块），避免两座岛贴在一起。 */
    public int plotGap = 32;

    /** 岛屿相对于方格中心的高度（Y）。 */
    public int islandY = 100;

    /** 每座岛的中心点相对于方格中心的 X / Z 偏移。 */
    public int islandOffsetX = 0;
    public int islandOffsetZ = 0;

    // ------------------------------------------------------------------
    // 海岛维度专用（自然海洋世界，不用网格）
    // ------------------------------------------------------------------

    /**
     * 海岛之间的最小间距（方块）。
     *
     * <p>海岛维度是一整片自然生成的海洋，岛是**按距离摆开**的，没有网格。
     * 这个值要大于"岛直径 + 一点余量"，否则两座岛会连在一起。
     */
    public int oceanSpacing = 320;

    /**
     * 海岛建在哪个高度。
     *
     * <p>0 表示"跟着海平面走"（运行时取该维度的海平面高度），
     * 这样即使换世界预设也不会让岛沉进海里或浮在天上。
     */
    public int oceanIslandY = 0;

    /** 掉到该 Y 以下就算掉虚空，送回岛上。 */
    public int voidY = 0;

    /** 首次进入空岛服是否自动分配并生成一座岛。 */
    public boolean autoCreateOnFirstJoin = true;

    /** 默认岛型 id。 */
    public String defaultType = "classic";

    /** 岛型列表。 */
    public List<IslandType> types = new ArrayList<>();

    // ------------------------------------------------------------------
    // 自然生成的岛屿（v0.4.0）
    // ------------------------------------------------------------------

    /**
     * 自然生成的岛屿：岛型 id → 坐标列表（每个元素是 {@code [x, z]}）。
     *
     * <p>每个位置会铺一座小岛，并在上面放**宝箱**与**刷怪笼**；
     * 配了 {@link #naturalIslandHouses} 还会放一座原版村庄房屋。
     * 默认位置都选在"离任何玩家岛 144 格以上"的地方（经典空岛的方格边界上），
     * 避免和玩家的岛撞在一起。
     */
    public Map<String, List<int[]>> naturalIslands = null;

    /** 自然岛的半径（方块，3~12）。 */
    public int naturalIslandRadius = 6;

    /**
     * 自然岛宝箱里的物资：{@code 物品id*数量}。
     *
     * <p>{@code null} = 用默认表（老配置文件走这条，自动升级）；**空数组 = 明确不要宝箱物资**。
     */
    public List<String> naturalIslandLoot = null;

    /** 自然岛上的刷怪笼生物 id（{@code null} = 默认；空数组 = 不放刷怪笼）。 */
    public List<String> naturalIslandSpawners = null;

    /** 自然岛上的村庄房屋（{@code null} = 默认；空数组 = 不放村庄）。 */
    public List<String> naturalIslandHouses = null;

    /** 默认的自然岛位置：经典空岛取方格边界（离任何玩家岛 ≥144 格），海岛放到 1600 格外。 */
    private static Map<String, List<int[]>> defaultNaturalIslands() {
        Map<String, List<int[]>> out = new LinkedHashMap<>();
        out.put("classic", List.of(
                new int[]{144, 144}, new int[]{-144, 144},
                new int[]{144, -144}, new int[]{432, 144}));
        out.put("ocean", List.of(
                new int[]{1600, 1600}, new int[]{-1600, 1600}, new int[]{1600, -1600}));
        return out;
    }

    /** 一座岛的生成配方。 */
    public static final class IslandType {
        /** 唯一 id。 */
        public String id = "classic";
        /** 显示名（支持 & 颜色代码）。 */
        public String displayName = "&a经典空岛";
        /** 简介（选择界面展示）。 */
        public String description = "";
        /** 方块层：{@code y偏移:方块id}，例如 {@code "0:minecraft:grass_block"}。 */
        public List<String> layers = new ArrayList<>();
        /**
         * 岸边的沙滩方块（海岛专用）。
         *
         * <p>沙滩是**水平方向**的材质变化 —— 同一层里靠岸的一圈是沙、里面是草，
         * 而 {@link #layers} 只能表达"第几层是什么方块"，表达不了这个，
         * 所以单独给一个字段。
         *
         * <p>用在海岛（{@code placement: ocean}）上：干地最外一圈、以及
         * 水面以下那几圈水下缓坡都用它。经典空岛（网格）用不上，留空即可。
         * 写 {@code none} 表示不要沙滩，干地全用层配置的顶面方块。
         */
        public String beach = "minecraft:sand";
        /** 生成一棵树。 */
        public boolean tree = true;
        /** 生成一个带初始物资的箱子。 */
        public boolean chest = true;
        /** 箱子里的物资：{@code 物品id*数量}。 */
        public List<String> loot = new ArrayList<>();

        /**
         * 这个岛屿维度是否配一座**自己的下界**。
         *
         * <p>默认开：空岛 / 海岛各有一界，玩家在里面拿到的东西不会和别的子服串。
         */
        public boolean ownNether = true;

        /**
         * 下界生成器预设。
         *
         * <ul>
         *   <li>{@code skyblock} —— 纯虚空 + 传送门落点补一座下界岩小岛（经典空岛用）；</li>
         *   <li>{@code lava_sea} —— 岩浆海：液面抬到 Y={@code 96}，低地全泡在岩浆里（海岛用）；</li>
         *   <li>{@code vanilla} —— 和原版一模一样的下界。</li>
         * </ul>
         *
         * <p>{@code null} = 按岛型给默认（空岛 skyblock、海岛 lava_sea）——
         * 老配置文件里没有这个键，走的就是这条，自动升级成新行为。
         */
        public String netherPreset = null;

        /** 这个岛屿维度是否配一座**自己的末地**（原版末地）。 */
        public boolean ownEnd = true;

        public IslandType() {
        }
    }

    /** 补齐默认值，保证配置损坏也能跑。 */
    public void normalize() {
        plotSize = Math.max(32, Math.min(plotSize, 4096));
        plotGap = Math.max(0, Math.min(plotGap, 1024));
        oceanSpacing = Math.max(64, Math.min(oceanSpacing, 4096));
        naturalIslandRadius = Math.max(3, Math.min(12, naturalIslandRadius));
        /*
         * null（老配置没有这个键）= 给默认；
         * 空 map 也给默认 —— 空 map 出现过的唯一原因是早期开发版把它写成 {}，
         * 不是管理员的选择。真想一座自然岛都不要，就把每种岛型都写成空列表。
         */
        if (naturalIslands == null || naturalIslands.isEmpty()) {
            naturalIslands = defaultNaturalIslands();
        }
        if (naturalIslandLoot == null) {
            naturalIslandLoot = new ArrayList<>(List.of(
                    "minecraft:iron_ingot*4", "minecraft:gold_ingot*2", "minecraft:bread*6",
                    "minecraft:torch*12", "minecraft:stone_sword*1", "minecraft:oak_planks*16",
                    "minecraft:bone_meal*8", "minecraft:diamond*1"));
        }
        if (naturalIslandSpawners == null) {
            naturalIslandSpawners = new ArrayList<>(List.of(
                    "minecraft:zombie", "minecraft:skeleton"));
        }
        if (naturalIslandHouses == null) {
            naturalIslandHouses = new ArrayList<>(List.of(
                    "minecraft:village/plains/houses/plains_small_house_1",
                    "minecraft:village/plains/houses/plains_small_house_2"));
        }
        if (types == null) {
            types = new ArrayList<>();
        }
        Map<String, IslandType> byId = new LinkedHashMap<>();
        for (IslandType type : types) {
            if (type == null || type.id == null || type.id.isBlank()) {
                continue;
            }
            type.id = type.id.trim().toLowerCase(java.util.Locale.ROOT);
            if (type.layers == null) {
                type.layers = new ArrayList<>();
            }
            if (type.loot == null) {
                type.loot = new ArrayList<>();
            }
            if (type.displayName == null || type.displayName.isBlank()) {
                type.displayName = type.id;
            }
            if (type.beach == null) {
                // null = 配置里没有这个键（老配置文件）→ 用默认沙滩。
                // 想关掉沙滩要显式写 "none" 或空串。
                type.beach = "minecraft:sand";
            }
            if (type.netherPreset == null || type.netherPreset.isBlank()) {
                // 老配置没有这个键 → 按岛型给默认：海岛岩浆海、其它（含经典空岛）虚空式
                type.netherPreset = "ocean".equals(type.id) ? "lava_sea" : "skyblock";
            }
            type.netherPreset = type.netherPreset.trim().toLowerCase(java.util.Locale.ROOT);
            /*
             * 老存档的**海岛物资**补齐树苗。
             *
             * 海岛默认不种树（preset 里 tree = false），所以箱子必须给树苗，
             * 否则玩家在岛上拿不到木头 —— 最基础的生存线是断的。
             * 这里按"未改动过的老默认表"精确匹配，只给没动过物资的老档补；
             * 管理员自己改过的物资表不会被覆盖。
             */
            if ("ocean".equals(type.id) && type.loot.containsAll(OLD_OCEAN_LOOT)
                    && !type.loot.contains("minecraft:oak_sapling*2")) {
                type.loot.add("minecraft:oak_sapling*2");
            }
            byId.putIfAbsent(type.id, type);
        }
        types = new ArrayList<>(byId.values());
        if (types.isEmpty()) {
            types.add(IslandPresets.classic());
            types.add(IslandPresets.ocean());
        }
        if (defaultType == null || byId.get(defaultType) == null) {
            defaultType = types.get(0).id;
        }
    }

    public IslandType type(String id) {
        if (id == null) {
            return types.isEmpty() ? IslandPresets.classic() : types.get(0);
        }
        String key = id.trim().toLowerCase(java.util.Locale.ROOT);
        return types.stream()
                .filter(t -> t.id.equals(key))
                .findFirst()
                .orElse(types.isEmpty() ? IslandPresets.classic() : types.get(0));
    }

    /** 方格边长（含间隔）。 */
    public int cellSize() {
        return plotSize + plotGap;
    }
}
