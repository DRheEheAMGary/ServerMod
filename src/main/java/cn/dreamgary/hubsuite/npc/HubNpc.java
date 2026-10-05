package cn.dreamgary.hubsuite.npc;

import cn.dreamgary.hubsuite.HubSuite;
import cn.dreamgary.hubsuite.world.PlayableWorld;
import cn.dreamgary.hubsuite.config.HubSuiteConfig;
import cn.dreamgary.hubsuite.fake.FakeConnection;
import cn.dreamgary.hubsuite.fake.FakeGamePacketListener;
import cn.dreamgary.hubsuite.world.SubServer;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * 大厅里代表某个子服的假人 NPC。
 *
 * <p>它就是一名"假的真玩家"：走 {@code PlayerList.placeNewPlayer} 的完整入服路径，
 * 因此会被自动加入区块追踪、对其他玩家可见、可被左键攻击 / 右键交互。
 * 与真玩家的三点差异见 {@link cn.dreamgary.hubsuite.fake.FakePlayers}。
 *
 * <p>NPC 不出现在玩家列表（Tab）里，也不会计入在线人数。
 */
public final class HubNpc {

    /** 名字前缀，避免和真实玩家重名。 */
    private static final String NAME_PREFIX = "hub_";

    private final SubServer target;
    private final HubSuiteConfig.NpcConfig config;
    private final String npcName;

    /**
     * 是否是"菜单假人"（点开箱子 GUI 选择服务器），而不是某个子服的直达入口。
     *
     * <p>菜单假人没有绑定子服，所以 {@link #target()} 会返回 null ——
     * 用到它的地方必须先判断这个标记。
     */
    private final boolean menuNpc;

    private ServerPlayer entity;

    /**
     * 是否已经发起过皮肤补全。
     *
     * <p>一次性：防止 {@code respawn() → spawn()} 再次注册回调造成无限重建
     * （SkinFetcher 缓存命中时回调内联执行，会把该 tick 卡死）。
     */
    private boolean skinFetchStarted;

    /**
     * 该假人所在维度的出生点；坐标配置是相对它的偏移。
     *
     * <p>由 {@code NpcManager} 在生成前设置 —— 它才知道这个维度属于哪个场所。
     */
    private PlayableWorld.SpawnPoint npcLevelAnchor;

    private HubNpc(SubServer target, HubSuiteConfig.NpcConfig config, String npcName, boolean menuNpc) {
        this.target = target;
        this.config = config;
        this.npcName = npcName;
        this.menuNpc = menuNpc;
    }

    public static HubNpc create(SubServer target) {
        String base = target.config().npc.name == null || target.config().npc.name.isBlank()
                ? target.id()
                : target.config().npc.name;
        String cleaned = base.replaceAll("\u00A7.", "")
                .replaceAll("&[0-9a-fk-orA-FOR]", "")
                .replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.length() < 3) {
            cleaned = target.id();
        }
        return new HubNpc(target, target.config().npc,
                sanitizeName(NAME_PREFIX + cleaned.toLowerCase(Locale.ROOT)), false);
    }

    /**
     * 创建一个"菜单假人"：点击后打开箱子 GUI（内含所有服务器），而不是直达某个子服。
     *
     * @param config  外观配置（坐标、名字、皮肤）
     * @param npcName 实体名（必须唯一，且只含 {@code [A-Za-z0-9_]}）
     */
    public static HubNpc createMenu(HubSuiteConfig.NpcConfig config, String npcName) {
        return new HubNpc(null, config, sanitizeName(npcName), true);
    }

    /**
     * 把实体名规整成**合法的 Minecraft 用户名**。
     *
     * <p><b>为什么必须做：</b>实体名会进入
     * {@code ClientboundPlayerInfoUpdatePacket}，而原版对名字的编码上限是
     * **16 个字符**（{@code Utf8String.write} 硬性检查）。
     * 超长会让包编码失败，客户端收到
     * {@code EncoderException: String too big} 并被**直接踢下线** ——
     * 而且是在"每个玩家连接时"都会触发，等于整个服务器进不去。
     *
     * <p>实测踩过：把空岛选岛假人取名 {@code hub_island_select}（17 字符），
     * 结果任何玩家一连上就掉线，日志报
     * {@code Failed to encode packet 'clientbound/minecraft:player_info_update'}。
     *
     * <p>所以名字只保留 {@code [A-Za-z0-9_]}，并强制 3..16 个字符。
     */
    public static String sanitizeName(String raw) {
        String cleaned = raw == null ? "" : raw
                .replaceAll("\u00A7.", "")
                .replaceAll("&[0-9a-fk-orA-FOR]", "")
                .replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.length() < 3) {
            cleaned = NAME_PREFIX + "npc";
        }
        if (cleaned.length() > MAX_NAME_LENGTH) {
            cleaned = cleaned.substring(0, MAX_NAME_LENGTH);
        }
        return cleaned;
    }

    /** 原版用户名上限。超过这个长度会让 player_info 包编码失败、玩家被踢。 */
    public static final int MAX_NAME_LENGTH = 16;

    /** 是否是菜单假人。 */
    public boolean isMenuNpc() {
        return menuNpc;
    }

    // ------------------------------------------------------------------
    // 生成 / 移除
    // ------------------------------------------------------------------

    /** 在大厅里生成 NPC；成功返回 true。 */
    public boolean spawn(MinecraftServer server, ServerLevel lobbyLevel) {
        if (entity != null && !entity.isRemoved()) {
            return true;
        }
        if (!config.enabled) {
            return false;
        }

        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(npcName), npcName);

        // 皮肤：先用缓存里的（同步），没有就先光着，等异步拉回来再重生一次
        SkinFetcher.cached(config.skin == null ? "" : config.skin)
                .ifPresent(skin -> SkinFetcher.apply(profile, skin));

        ClientInformation info = new ClientInformation(
                "zh_cn",
                2,
                ChatVisiblity.HIDDEN,
                false,
                0x7f,
                Player.DEFAULT_MAIN_HAND,
                false,
                false,                      // allowsListing=false → 不出现在 Tab 列表
                net.minecraft.server.level.ParticleStatus.MINIMAL);

        ServerPlayer npc = new cn.dreamgary.hubsuite.fake.MarkedFakePlayer(
                server, lobbyLevel, profile, info,
                menuNpc ? "大厅NPC:菜单" : "大厅NPC:" + (target == null ? "?" : target.id()));
        FakeConnection connection = new FakeConnection();
        npc.connection = new FakeGamePacketListener(server, connection, npc);

        try {
            server.getPlayerList().placeNewPlayer(connection, npc,
                    new net.minecraft.server.network.CommonListenerCookie(profile, 0, info, false));
        } catch (Throwable t) {
            HubSuite.logger().error("生成大厅 NPC '{}' 失败", npcName, t);
            return false;
        }

        // 注意：placeNewPlayer 会调用 snapTo 覆盖坐标与朝向，所以位置/朝向必须在这之后再设
        /*
         * 假人坐标是**相对该维度出生点的偏移**，不是绝对坐标。
         *
         * 踩过的坑：配置注释一直写"相对大厅出生点"，代码却直接当绝对坐标用。
         * 主大厅恰好出生点 Y=100、配置 Y=100，看起来是对的；但空岛服大厅的
         * 出生点是 Y=101（地板在 100），配置里还是 100 ——
         * 于是假人**下半身埋在地板里**（用户实测反馈）。
         *
         * 改成真正的偏移后，两个大厅都自动站在地面上。
         */
        double baseX = config.x;
        double baseY = config.y;
        double baseZ = config.z;
        if (npcLevelAnchor != null) {
            baseX += npcLevelAnchor.x();
            baseY += npcLevelAnchor.y();
            baseZ += npcLevelAnchor.z();
        }
        npc.setPos(baseX, baseY, baseZ);
        npc.snapTo(baseX, baseY, baseZ, config.yaw, config.pitch);
        npc.setYHeadRot(config.yaw);
        npc.setCustomName(cn.dreamgary.hubsuite.ui.Text.of(displayName()));
        npc.setCustomNameVisible(true);
        HubSuite.logger().info("NPC '{}'：allowsListing={}（Tab 隐藏由 PlayerInfoPacketMixin 负责）",
                config.name, npc.allowsListing());
        // 不参与任何伤害/饥饿逻辑
        npc.setInvulnerable(true);
        npc.getInventory().clearContent();

        this.entity = npc;
        HubSuite.logger().info("大厅 NPC '{}' 已生成于 ({}, {}, {})，对应子服 '{}'。",
                npcName, baseX, baseY, baseZ,
                menuNpc ? "(菜单)" : String.valueOf(target == null ? "?" : target.id()));

        // 异步补皮肤：拿到之后重建 NPC，让玩家看到正确皮肤。
        // 注意这里**不阻塞**服务端启动 —— HTTP 请求在后台线程，回调才回主线程。
        if (config.skin != null && !config.skin.isBlank() && !"none".equalsIgnoreCase(config.skin)) {
            String skinName = "auto".equalsIgnoreCase(config.skin) ? npcName : config.skin;
            /*
             * 补皮肤必须只做一次。
             *
             * respawn() = despawn + spawn，而 spawn 又会走到这里再注册一次回调；
             * SkinFetcher 缓存命中时返回的是 completedFuture，thenAccept 会在
             * 调用线程**内联**执行 → execute() → 任务重新入队 →
             * BlockableEventLoop 的 while(pollTask()) 永远退不出来，该 tick 卡死，
             * 最终被看门狗以 "single server tick took 60.00 seconds" 强杀。
             * 即便退化成跨 tick，也是每 tick 无限 despawn+重建实体。
             *
             * 触发条件只是 config.skin 能被解析成功（真实账号名，或 "auto"
             * 且实体名恰好是真实账号）—— 属于"改配置即炸"的定时炸弹。
             */
            if (skinFetchStarted) {
                return true;
            }
            skinFetchStarted = true;

            SkinFetcher.fetchAsync(skinName).thenAccept(skin -> skin.ifPresent(s -> {
                MinecraftServer srv = server;
                srv.execute(() -> {
                    if (entity != null && !entity.isRemoved()) {
                        SkinFetcher.apply(entity.getGameProfile(), s);
                        respawn(server, lobbyLevel);
                    }
                });
            }));
        }
        return true;
    }

    /** 重建实体（用于应用皮肤 / 位置变更）。 */
    /** 设置坐标偏移的基准（该维度的出生点）。 */
    public void setLevelAnchor(PlayableWorld.SpawnPoint anchor) {
        this.npcLevelAnchor = anchor;
    }

    public void respawn(MinecraftServer server, ServerLevel lobbyLevel) {
        despawn();
        spawn(server, lobbyLevel);
    }

    public void despawn() {
        if (entity == null) {
            return;
        }
        try {
            entity.level().getServer().getPlayerList().remove(entity);
        } catch (Throwable t) {
            HubSuite.logger().debug("移除 NPC '{}' 时出错：{}", npcName, t.toString());
        }
        entity = null;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    public SubServer target() {
        return target;
    }

    public String npcName() {
        return npcName;
    }

    public ServerPlayer entity() {
        return entity;
    }

    public boolean alive() {
        return entity != null && !entity.isRemoved();
    }

    /** 头顶显示的名字（配置文件里的颜色代码会被解析）。 */
    public String displayName() {
        if (config.name != null && !config.name.isBlank()) {
            return config.name;
        }
        return target == null ? npcName : target.displayName();
    }

    public HubSuiteConfig.NpcConfig config() {
        return config;
    }

    /** 让 NPC 转向最近的玩家（大厅里更好看）。 */
    public void faceNearestPlayer() {
        if (entity == null || !config.lookAtNearestPlayer) {
            return;
        }
        ServerPlayer nearest = null;
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : entity.level().players()) {
            if (player == entity) {
                continue;
            }
            double distance = player.distanceToSqr(entity);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        if (nearest == null || best > 64 * 64) {
            return;
        }
        double dx = nearest.getX() - entity.getX();
        double dz = nearest.getZ() - entity.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.setXRot(0.0F);
    }
}
