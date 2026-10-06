package cn.dreamgary.hubsuite.config;

import cn.dreamgary.hubsuite.HubSuite;
import java.util.ArrayList;
import java.util.List;

/**
 * HubSuite 的根配置，序列化到 {@code config/hubsuite/config.json}。
 *
 * <p>这是一个纯数据容器（Gson 直接反射填充），因此：
 * <ul>
 *   <li>所有字段都有安全的默认值，配置文件缺字段不会导致 NPE；</li>
 *   <li>{@link #normalize()} 负责把非法/越界的值修正回默认值。</li>
 * </ul>
 *
 * <p><b>扩展方式：</b>新增一个子服 → 往 {@link #servers} 里加一条 {@link SubServerConfig}，
 * 不需要改任何 Java 代码。
 */
public final class HubSuiteConfig {

    /** 配置文件结构版本，将来做迁移时用。 */
    public int configVersion = 1;

    public LobbyConfig lobby = new LobbyConfig();
    public AuthConfig auth = new AuthConfig();
    /** 空岛系统（作用于 servers 里 id 匹配的那一个子服）。 */
    public cn.dreamgary.hubsuite.island.IslandConfig island =
            cn.dreamgary.hubsuite.island.IslandPresets.defaults();
    public List<SubServerConfig> servers = new ArrayList<>();

    /** 大厅：玩家登录成功后所在的场所。 */
    /** 任务系统配置（定义 + 开关）。 */
    public cn.dreamgary.hubsuite.quest.QuestPresets.QuestConfig quests =
            cn.dreamgary.hubsuite.quest.QuestPresets.defaults();

    public static final class LobbyConfig {

        /**
         * 大厅里那个"点击传送"引导假人的外观配置。
         *
         * <p>它没有绑定任何子服 —— 点击后打开箱子界面，在界面里选服务器。
         * 这样以后新增子服只需要改界面内容，不用再摆新假人。
         */
        public NpcConfig menuNpc = null;
        /** 大厅所在存档目录名（相对服务端根目录）。 */
        public String saveName = "hubsuite_lobby";
        /**
         * 大厅维度类型。
         *
         * <p><b>必须用 {@code minecraft:overworld}，不要用 {@code minecraft:the_end}</b> ——
         * 末地维度类型会让原版强制把玩家放在 Y=1 的黑曜石平台上并生成末影龙，
         * 我们配置的出生点会被直接覆盖（这个坑实测踩过）。
         * 想要虚空只需要 {@link #voidGenerator} = true。
         */
        public String dimensionType = "minecraft:overworld";
        /** 是否用纯虚空生成器（覆盖 dimensionType 的地形，只保留维度属性）。 */
        public boolean voidGenerator = true;
        /** 世界种子。 */
        public long seed = 0L;
        public double spawnX = 0.5;
        public double spawnY = 100.0;
        public double spawnZ = 0.5;
        public float spawnYaw = 0.0F;
        public float spawnPitch = 0.0F;
        /** 大厅规则：禁止一切伤害。 */
        public boolean invulnerable = true;
        /** 是否强制冒险模式。 */
        public boolean adventureMode = true;
        /** 是否在出生点自动生成一个带灯光的平台（强烈建议开启，否则玩家会在虚空里）。 */
        public boolean buildPlatform = true;
        /** 平台半径（方块）。 */
        public int platformRadius = 10;
    }

    /** 登录/注册相关。Phase 2 使用。 */
    public static final class AuthConfig {
        /** 是否启用登录系统。 */
        public boolean enabled = true;
        /** 未登录玩家的等待时间（秒），超时踢出。 */
        public int loginTimeoutSeconds = 120;
        /** 同一 UUID+IP 的会话保持时长（分钟），0 = 每次都要登录。 */
        public int sessionMinutes = 60;
        /** 连续失败多少次后锁定。 */
        public int maxFailures = 5;
        /** 锁定时长（分钟）。 */
        public int lockoutMinutes = 10;
        /** 密码最短长度。 */
        public int minPasswordLength = 6;
        /** 密码最长长度（上限受对话框 TextInput 的 max_length 限制，最大 255）。 */
        public int maxPasswordLength = 32;
        /** PBKDF2 迭代次数。 */
        public int pbkdf2Iterations = 120_000;
        /** 是否允许同一个 IP 注册多个账号。 */
        public boolean allowMultipleAccountsPerIp = true;
    }

    /** 单个子服的配置。 */
    public static final class SubServerConfig {
        /** 唯一 id，同时用作存档目录名：{@code <服务端根目录>/hubsuite_servers/<id>}。 */
        public String id = "survival";
        /** 显示名（支持 & 颜色代码）。 */
        public String displayName = "&a生存服";
        /** 是否启用。false 时不会创建世界，也不会出现在大厅里。 */
        public boolean enabled = true;
        /** 大厅 NPC 里的排序，越小越靠前。 */
        public int order = 0;

        /** 主世界生成方式，见 {@link WorldKind} 对应的字符串。 */
        public String worldKind = "normal";
        /** 仅 {@code flat} 时生效：从下到上的层，格式 {@code 方块id*高度}，例如 {@code minecraft:bedrock*1}。 */
        public List<String> flatLayers = new ArrayList<>(List.of("minecraft:bedrock*1"));
        /**
         * 该子服是否拥有**自己独立的下界**。
         *
         * <p>默认开着：每个子服"完全独立"是本模组的核心承诺，
         * 而下界/末地是玩家能拿到资源、能互相串门的地方 ——
         * 共用一个下界等于把三个子服连通了（在生存服挖的下界通道，
         * 创造服能直接走过去）。所以默认给每个子服各自一份。
         *
         * <p>关掉的话，传送门会退回原版行为（全服共用一个下界）。
         */
        public boolean ownNether = true;
        /** 该子服是否拥有**自己独立的末地**。语义同 {@link #ownNether}。 */
        public boolean ownEnd = true;

        public long seed = 0L;

        /**
         * 是否使用世界自身的地形出生点。
         *
         * <p><b>normal 世界建议保持 true</b>：原版生成器会算出地表高度并放在实地上，
         * 不需要人工指定 Y。硬写 Y 很容易悬空（实测踩过"进生存服就摔死"）。
         * 只有 flat / void 这类"地形高度已知或压根没有地形"的世界才需要设 false。
         */
        public boolean useWorldSpawn = true;

        /**
         * 原版出生点的缓存（由模组自动写入，管理员一般不用改）。
         *
         * <p>为什么要缓存：自定义维度的出生点写在 {@code level.dat} 里并不可靠
         * （{@code getRespawnData()} 可能返回主世界的值），实测导致
         * "每次启动读回一个悬空的 Y=100"。存在自己的配置里就没有这个问题。
         *
         * <p>值为 {@code null} 表示还没算过，下次启动会用原版算一次并写回。
         */
        public Double resolvedSpawnX = null;
        public Double resolvedSpawnY = null;
        public Double resolvedSpawnZ = null;

        /**
         * 上面那份出生点缓存是在哪个种子下算出来的。
         *
         * <p>换了种子地形就完全不同，旧坐标会落在海里/山里 —— 必须失效重算。
         */
        public Long resolvedSpawnSeed = null;

        public double spawnX = 0.5;
        public double spawnY = 64.0;
        public double spawnZ = 0.5;
        public float spawnYaw = 0.0F;
        public float spawnPitch = 0.0F;

        /** 原版游戏规则；键名用大写常量名，例如 {@code KEEP_INVENTORY}。只写你想改的项。 */
        public java.util.Map<String, String> gameRules = new java.util.LinkedHashMap<>();

        /** mod 自有的子服规则（原版 gamerule 表达不了的）。 */
        public ServerBehaviour behaviour = new ServerBehaviour();

        /** 玩家进入该子服时的默认游戏模式。 */
        public String gameMode = "survival";
        /** 该子服的难度：peaceful / easy / normal / hard。 */
        public String difficulty = "normal";
        /** 是否强制该游戏模式（true = 每次进入都设置）。 */
        public boolean forceGameMode = false;
        /** 是否允许玩家用 /gamemode 自行切换。 */
        public boolean allowGameModeCommand = false;

        /** 进入该子服是否回到上次退出的位置（false = 每次回到出生点）。 */
        public boolean rememberLastLocation = true;
        /** 该子服的权限组名（接 LuckPerms 时用）。 */
        public String permissionGroup = "hubsuite.survival";

        /**
         * 是否允许在该子服使用 HuskHomes 的传送指令（/home /spawn /tpa /warp）。
         * 空岛服/创造服通常应设为 false，避免玩家把自己传到别的子服或逃出保护范围。
         */
        public boolean huskhomesTeleport = true;

        /** 大厅 NPC 的位置（在大厅坐标系里）；null 表示不生成 NPC。 */
        public NpcConfig npc = new NpcConfig();
    }

    /** mod 自有的子服规则。 */
    public static final class ServerBehaviour {
        /** 玩家之间能否互相伤害。 */
        public boolean pvp = false;
        /** 是否禁止一切伤害（大厅/创造常用）。 */
        public boolean invulnerable = false;
        /** 是否禁止摔落伤害。 */
        public boolean fallDamage = true;
        /** 是否禁止爆炸破坏地形。 */
        public boolean explosionBlockDamage = true;
        /** 是否禁止火焰蔓延。 */
        public boolean fireSpread = true;
        /** 是否禁止生物破坏方块（苦力怕/末影人）。 */
        public boolean mobGriefing = true;
        /** 是否禁止 TNT 点燃。 */
        public boolean tntIgnition = true;
        /** 是否禁止丢弃物品（空岛/创造可选）。 */
        public boolean allowItemDrop = true;
        /** 是否允许飞行。 */
        public boolean allowFlight = false;
        /** 是否清空玩家背包（进入时）。 */
        public boolean clearInventoryOnEnter = false;
        /** 是否让玩家保持满饥饿度（创造/大厅）。 */
        public boolean keepHungerFull = false;
        /** 世界边界半径（方块），0 = 不设置。 */
        public double worldBorderRadius = 0.0;
    }

    /** 大厅里代表某个子服的假人 NPC。 */
    public static final class NpcConfig {
        /** 是否生成。 */
        public boolean enabled = true;
        /**
         * 坐标 —— **相对所在维度出生点的偏移**，不是绝对坐标。
         *
         * <p>用偏移是为了让同一个配置在不同维度都对：主大厅地板在 Y=99、
         * 出生点在 Y=100；空岛服大厅地板在 Y=100、出生点在 Y=101。
         * 写绝对坐标的话，其中一边必然把假人埋进地板里（实测踩过）。
         */
        public double x = 0.0;
        public double y = 0.0;
        public double z = 5.0;
        public float yaw = 0.0F;
        public float pitch = 0.0F;
        /** NPC 头顶名字（空 = 用 displayName）。支持 & 颜色代码。 */
        public String name = "";
        /** NPC 皮肤：{@code auto} = 按名字拉取正版皮肤；也可以填 {@code 正版玩家名}；{@code none} = 默认皮肤。 */
        public String skin = "auto";
        /** 是否面向最近的玩家（大厅里比较好看）。 */
        public boolean lookAtNearestPlayer = true;
    }

    /** 生成器类型。 */
    public enum WorldKind {
        /** 原版地形。 */
        NORMAL,
        /** 超平坦（由 flatLayers 决定）。 */
        FLAT,
        /** 纯虚空（空岛/大厅用）。 */
        VOID;

        public static WorldKind parse(String raw, WorldKind fallback) {
            if (raw == null) {
                return fallback;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /** 把非法值修正回安全值。 */
    /**
     * 保证各个"正常世界"子服用**互不相同**的种子。
     *
     * <p>为什么需要：默认配置里所有子服的 {@code seed} 都是 0，
     * 而同种子 + 同算法 = **同一张地图**。结果生存服和创造服长得一模一样，
     * 玩家还能靠生存服的地形去创造服提前找矿（用户实测反馈）。
     *
     * <p>注意：**改种子不会改变已经生成的地形**。存档里的区块已经落盘了，
     * 只有新探索的区域会按新种子生成 —— 中间会出现明显的地形断层。
     * 想要干净的新地形必须删掉对应存档目录，这里只负责把配置改对并告警。
     */
    private void normalizeSeeds() {
        if (servers == null) {
            return;
        }
        java.util.Map<Long, java.util.List<SubServerConfig>> bySeed = new java.util.LinkedHashMap<>();
        for (SubServerConfig sub : servers) {
            if (sub == null || !"normal".equalsIgnoreCase(sub.worldKind)) {
                continue;   // 虚空/超平坦用不上种子
            }
            bySeed.computeIfAbsent(sub.seed, k -> new java.util.ArrayList<>()).add(sub);
        }
        long[] distinct = {20260101L, 20260202L, 20260303L, 20260404L, 20260505L};
        int next = 0;
        for (var entry : bySeed.entrySet()) {
            if (entry.getValue().size() <= 1) {
                continue;
            }
            // 同种子的第一个保留，其余分配新值
            for (int i = 1; i < entry.getValue().size(); i++) {
                SubServerConfig sub = entry.getValue().get(i);
                long candidate = distinct[Math.min(next++, distinct.length - 1)];
                while (candidate == entry.getKey()) {
                    candidate++;
                }
                HubSuite.logger().warn(
                        "子服 '{}' 与其它子服共用种子 {}（会同生成同一张地图），已改为 {}。"
                                + "注意：已生成的区块不会变，想要干净地形请删除存档目录 hubsuite_{}。",
                        sub.id, entry.getKey(), candidate, sub.id);
                sub.seed = candidate;
            }
        }
    }

    /**
     * 把"老式绝对坐标"的假人配置迁移成相对偏移。
     *
     * <p>空岛服大厅用的是独立的 {@link cn.dreamgary.hubsuite.island.IslandConfig.HubNpcConfig}，
     * 所以这里做一个重载 —— 漏掉任何一类配置都会得到
     * "偏移叠在绝对坐标上"的离谱位置（实测：空岛假人跑到 Y=201）。
     */
    private static void migrateNpcOffsets(
            cn.dreamgary.hubsuite.island.IslandConfig.HubNpcConfig npc) {
        if (npc == null) {
            return;
        }
        if (Math.abs(npc.y) > 10.0) {
            npc.y = 0.0;
        }
        if (Math.abs(npc.x) > 8.0) {
            npc.x = 0.0;
        }
        if (Math.abs(npc.z) > 12.0) {
            npc.z = 5.0;
        }
    }

    private static void migrateNpcOffsets(NpcConfig npc) {
        if (npc == null) {
            return;
        }
        if (Math.abs(npc.y) > 10.0) {
            npc.y = 0.0;          // 原本想表达"站在地面上"
        }
        // X/Z 同理：绝对值很大说明是绝对坐标，收敛到"在出生点正前方 5 格"
        if (Math.abs(npc.x) > 8.0) {
            npc.x = 0.0;
        }
        if (Math.abs(npc.z) > 12.0) {
            npc.z = 5.0;
        }
    }

    public void normalize() {
        if (lobby == null) {
            lobby = new LobbyConfig();
        }
        if (auth == null) {
            auth = new AuthConfig();
        }
        if (island == null) {
            island = cn.dreamgary.hubsuite.island.IslandPresets.defaults();
        }
        island.normalize();
        // 假人坐标历史上被当成绝对坐标用过（默认 y=100）。
        // 这里把明显是绝对坐标的值迁移成偏移：Y>10 只可能是绝对高度，
        // 而它的本意就是"站在地面上"，也就是偏移 0。
        migrateNpcOffsets(lobby == null ? null : lobby.menuNpc);
        // 空岛服大厅的选岛假人、以及每个子服自己的 NPC 配置，同样要迁移 ——
        // 漏掉任何一个都会得到一个"偏移叠在绝对坐标上"的离谱位置
        // （实测：空岛假人跑到了 Y=201）。
        if (island != null) {
            migrateNpcOffsets(island.hubNpc);
        }
        if (servers != null) {
            for (SubServerConfig sub : servers) {
                if (sub != null) {
                    migrateNpcOffsets(sub.npc);
                }
            }
        }
        normalizeSeeds();
        // 任务定义必须 normalize（内部会调用 Quest.compile()，
        // 把 "break:minecraft:oak_log" 这样的字符串拆成目标类型 + 参数）。
        // 漏掉的话从磁盘读回来的任务全都是 UNKNOWN 目标，进度永远不动。
        if (quests == null) {
            quests = cn.dreamgary.hubsuite.quest.QuestPresets.defaults();
        }
        quests.normalize();
        if (servers == null) {
            servers = new ArrayList<>();
        }
        auth.minPasswordLength = Math.max(1, Math.min(auth.minPasswordLength, 255));
        auth.maxPasswordLength = Math.max(auth.minPasswordLength, Math.min(auth.maxPasswordLength, 255));
        auth.pbkdf2Iterations = Math.max(10_000, auth.pbkdf2Iterations);
        auth.loginTimeoutSeconds = Math.max(5, auth.loginTimeoutSeconds);

        java.util.Set<String> seen = new java.util.HashSet<>();
        List<SubServerConfig> cleaned = new ArrayList<>(servers.size());
        for (SubServerConfig s : servers) {
            if (s == null || s.id == null || s.id.isBlank()) {
                continue;
            }
            s.id = s.id.trim().toLowerCase(java.util.Locale.ROOT);
            if (!s.id.matches("[a-z0-9_\\-]{1,48}")) {
                cn.dreamgary.hubsuite.HubSuite.logger()
                        .warn("子服 id '{}' 含非法字符（只允许 a-z 0-9 _ -），已跳过。", s.id);
                continue;
            }
            if (!seen.add(s.id)) {
                cn.dreamgary.hubsuite.HubSuite.logger().warn("子服 id '{}' 重复，已跳过后面那一条。", s.id);
                continue;
            }
            if (s.displayName == null || s.displayName.isBlank()) {
                s.displayName = s.id;
            }
            if (s.gameRules == null) {
                s.gameRules = new java.util.LinkedHashMap<>();
            }
            if (s.behaviour == null) {
                s.behaviour = new ServerBehaviour();
            }
            if (s.npc == null) {
                s.npc = new NpcConfig();
            }
            if (s.flatLayers == null) {
                s.flatLayers = new ArrayList<>();
            }
            cleaned.add(s);
        }
        cleaned.sort(java.util.Comparator.comparingInt(s -> s.order));
        servers = cleaned;
    }
}
