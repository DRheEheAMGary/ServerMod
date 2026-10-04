package cn.dreamgary.hubsuite.fake;

import cn.dreamgary.hubsuite.HubSuite;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 假玩家的"网络连接"。
 *
 * <p>它什么都不发：所有出站包都被丢弃。存在的唯一目的是让原版那套
 * "玩家必须有连接"的检查（{@code connection.isConnected()}、玩家列表维护、
 * 区块跟随、末影珍珠传送等）成立，从而让假玩家能以**真实玩家的完整路径**
 * 加入服务端 —— 这比手搓一个"半个玩家"稳定得多。
 *
 * <p>做法参考了主流假人实现（Carpet 的 {@code FakeClientConnection}）：
 * 注入一个 {@link EmbeddedChannel} 让 {@code isConnected()} 返回 true，
 * 其余发送/协议切换全部变成空操作。
 */
public class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        // isConnected() == (channel != null && channel.isOpen())
        ((cn.dreamgary.hubsuite.mixin.ConnectionChannelAccessor) this)
                .hubsuite$setChannel(new EmbeddedChannel());
    }

    @Override
    public void send(Packet<?> packet) {
        // 假玩家不接收任何包
    }

    @Override
    public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener) {
        // 同上
    }

    @Override
    public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) {
        // 同上
    }

    @Override
    public void flushChannel() {
        // 没有真实通道需要 flush
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
        // 假玩家不经过握手阶段
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        // 假玩家不接受来自客户端的协议切换
    }

    @Override
    public void disconnect(net.minecraft.network.chat.Component message) {
        HubSuite.logger().debug("假玩家连接收到断开请求（已忽略）：{}", message.getString());
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return new InetSocketAddress("127.0.0.1", 0);
    }

    @Override
    public boolean isMemoryConnection() {
        return true;
    }
}
