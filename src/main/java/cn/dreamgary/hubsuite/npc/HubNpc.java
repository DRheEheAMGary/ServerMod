package cn.dreamgary.hubsuite.npc;

import cn.dreamgary.hubsuite.HubSuite;
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
        if (cleaned.length() > 11) {
            cleaned = cleaned.substring(0, 11);
        }
        return new HubNpc(target, target.config().npc, NAME_PREFIX + cleaned.toLowerCase(Locale.ROOT), false);
    }

    /**
     * 创建一个"菜单假人"：点击后打开箱子 GUI（内含所有服务器），而不是直达某个子服。
     *
     * @param config  外观配置（坐标、名字、皮肤）
     * @param npcName 实体名（必须唯一，且只含 {@code [A-Za-z0-9_]}）
     */
    public static HubNpc createMenu(HubSuiteConfig.NpcConfig config, String npcName) {
        return new HubNpc(null, config, npcName, true);
    }

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
        npc.setPos(config.x, config.y, config.z);
        npc.snapTo(config.x, config.y, config.z, config.yaw, config.pitch);
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
                npcName, config.x, config.y, config.z,
                menuNpc ? "(菜单)" : String.valueOf(target == null ? "?" : target.id()));

        // 异步补皮肤：拿到之后重建 NPC，让玩家看到正确皮肤。
        // 注意这里**不阻塞**服务端启动 —— HTTP 请求在后台线程，回调才回主线程。
        if (config.skin != null && !config.skin.isBlank() && !"none".equalsIgnoreCase(config.skin)) {
            String skinName = "auto".equalsIgnoreCase(config.skin) ? npcName : config.skin;
            SkinFetcher.fetchAsync(skinName).thenAccept(skin -> skin.ifPresent(s -> {
                MinecraftServer srv = server;
                srv.execute(() -> {
                    if (entity != null) {
                        SkinFetcher.apply(entity.getGameProfile(), s);
                        respawn(server, lobbyLevel);
                    }
                });
            }));
        }
        return true;
    }

    /** 重建实体（用于应用皮肤 / 位置变更）。 */
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
