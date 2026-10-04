package cn.dreamgary.hubsuite.fake;

import cn.dreamgary.hubsuite.HubSuite;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

/**
 * 假玩家工厂。
 *
 * <p>走的是**和真实玩家完全一样的加入路径**（{@code PlayerList.placeNewPlayer}），
 * 所以能自动获得：区块跟随、实体追踪（别的玩家看得见他）、玩家数据落盘等全部行为。
 * 与真实玩家的区别只有三点：
 * <ol>
 *   <li>连接是 {@link FakeConnection}（出站包全部丢弃）；</li>
 *   <li>{@code allowsListing=false}，因此不出现在玩家列表（Tab）里；</li>
 *   <li>被踢的路径被 {@link FakeGamePacketListener} 屏蔽。</li>
 * </ol>
 */
public final class FakePlayers {

    private FakePlayers() {
    }

    /**
     * 创建一个假玩家并让他"加入"服务端。
     *
     * @param name       名字（离线 UUID 由名字推导，名字必须唯一）
     * @param level      加入哪个维度
     * @param visibleInTab 是否出现在玩家列表里（大厅 NPC 通常设为 false）
     */
    public static ServerPlayer spawn(MinecraftServer server, String name, ServerLevel level, boolean visibleInTab) {
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);

        ClientInformation info = new ClientInformation(
                "zh_cn",
                2,                      // viewDistance：NPC 不需要看多远
                ChatVisiblity.HIDDEN,
                false,
                0x7f,                   // 显示全部皮肤层
                Player.DEFAULT_MAIN_HAND,
                false,
                visibleInTab,           // allowsListing
                net.minecraft.server.level.ParticleStatus.MINIMAL);

        ServerPlayer player = new MarkedFakePlayer(server, level, profile, info, "自检/工具:" + name);
        FakeConnection connection = new FakeConnection();
        player.connection = new FakeGamePacketListener(server, connection, player);

        try {
            server.getPlayerList().placeNewPlayer(
                    connection,
                    player,
                    new net.minecraft.server.network.CommonListenerCookie(profile, 0, info, false));
        } catch (Throwable t) {
            HubSuite.logger().error("假玩家 '{}' 加入服务端失败。", name, t);
            return null;
        }

        HubSuite.logger().debug("假玩家 '{}' 已加入维度 {}。", name, level.dimension().identifier());
        return player;
    }

    /** 按显示名生成一个合法的假玩家名（长度 1..16，去颜色代码与空格）。 */
    public static String sanitizeName(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String cleaned = raw.replaceAll("\u00A7.", "")
                .replaceAll("&[0-9a-fk-orA-FK-OR]", "")
                .replaceAll("[^A-Za-z0-9_]", "");
        if (cleaned.length() < 3) {
            cleaned = fallback + cleaned;
        }
        if (cleaned.length() > 16) {
            cleaned = cleaned.substring(0, 16);
        }
        return cleaned.isEmpty() ? fallback : cleaned.toLowerCase(Locale.ROOT);
    }
}
