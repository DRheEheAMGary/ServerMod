package cn.dreamgary.hubsuite.fake;

import cn.dreamgary.hubsuite.HubSuite;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * 假玩家的"游戏阶段监听器"。
 *
 * <p>它不会被原版的保活/挂机踢人机制干掉：
 * <ul>
 *   <li>{@code disconnect} 被改写成只记日志 —— 因此
 *       {@code disconnect.timeout}（保活超时）、{@code multiplayer.disconnect.idling}
 *       （挂机）、{@code duplicate_login}（重名）都不会真的踢掉 NPC；</li>
 *   <li>不注册到真实的网络层，所以不占端口、不耗 CPU 于收发。</li>
 * </ul>
 */
public class FakeGamePacketListener extends ServerGamePacketListenerImpl {

    public FakeGamePacketListener(MinecraftServer server, Connection connection, ServerPlayer player) {
        super(server, connection, player,
                new CommonListenerCookie(player.getGameProfile(), 0, player.clientInformation(), false));
    }

    /** 屏蔽所有"被踢"路径。 */
    @Override
    public void disconnect(Component message) {
        HubSuite.logger().debug("假玩家 [{}] 的断开请求已被忽略：{}",
                player.getName().getString(), message.getString());
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        HubSuite.logger().debug("假玩家 [{}] 的断开请求已被忽略：{}",
                player.getName().getString(), details.reason().getString());
    }
}
