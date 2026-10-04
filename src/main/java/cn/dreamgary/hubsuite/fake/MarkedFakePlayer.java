package cn.dreamgary.hubsuite.fake;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 带"假玩家"标记的 {@link ServerPlayer}。
 *
 * <p>服务端把假玩家当真正的玩家对待（这正是大厅 NPC 能正常显示、被点击、
 * 被区块追踪的原因），但也因此会把它们算进在线人数统计里。
 * 用一个显式子类 + {@link HubSuiteFakePlayer} 标记接口，
 * 就能让 {@code PlayerListFakePlayerMixin} 精确地把它们过滤掉，
 * 而不必去改名字或 UUID（那会牵连玩家数据文件的命名）。
 */
public class MarkedFakePlayer extends ServerPlayer implements HubSuiteFakePlayer {

    private final String purpose;

    public MarkedFakePlayer(MinecraftServer server, ServerLevel level,
                            GameProfile profile, ClientInformation information, String purpose) {
        super(server, level, profile, information);
        this.purpose = purpose;
    }

    @Override
    public String hubsuite$fakePurpose() {
        return purpose;
    }
}
