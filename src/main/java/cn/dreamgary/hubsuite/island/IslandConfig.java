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

    /** 是否开启方格保护（非岛主不能破坏/放置）。 */
    public boolean protectPlots = true;

    /** 掉到该 Y 以下就算掉虚空，送回岛上。 */
    public int voidY = 0;

    /** 首次进入空岛服是否自动分配并生成一座岛。 */
    public boolean autoCreateOnFirstJoin = true;

    /** 默认岛型 id。 */
    public String defaultType = "classic";

    /** 岛型列表。 */
    public List<IslandType> types = new ArrayList<>();

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

        public IslandType() {
        }
    }

    /** 补齐默认值，保证配置损坏也能跑。 */
    public void normalize() {
        plotSize = Math.max(32, Math.min(plotSize, 4096));
        plotGap = Math.max(0, Math.min(plotGap, 1024));
        oceanSpacing = Math.max(64, Math.min(oceanSpacing, 4096));
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
